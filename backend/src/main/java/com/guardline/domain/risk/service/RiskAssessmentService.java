package com.guardline.domain.risk.service;

import com.guardline.domain.risk.client.LlmGatewayClient;
import com.guardline.domain.risk.client.SignalDetectionResult;
import com.guardline.domain.risk.response.RiskAssessmentResponseDTO;
import com.guardline.domain.risk.response.StageEvidenceResponseDTO;
import com.guardline.global.config.LlmProperties;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 통화 세션별로 확정 문장을 모아 위험도를 판정한다.
 *
 * <p>문장이 확정될 때마다 LLM을 부르면 비용과 지연이 감당이 안 되므로 디바운스를 건다.
 * 단계 신호는 통화 종료까지 래치한다. 실제 사기 통화는 사칭 시점과 이체 요구 시점 사이가
 * 20~40분 벌어지므로 시간 감쇠를 넣으면 탐지에 실패한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAssessmentService {

    private final LlmGatewayClient llmGatewayClient;
    private final RiskScorer riskScorer;
    private final LlmProperties llmProperties;

    private final Map<String, CallState> calls = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * 통화 시작. inbound=false면 사용자가 건 전화라 감점 신호 N3이 적용된다.
     *
     * <p>판정은 고정 주기로 돈다. 확정 문장마다 타이머를 미루는 디바운스 방식은 문장이 주기보다
     * 자주 들어오는 실제 통화에서 타이머가 매번 리셋돼 판정이 한 번도 실행되지 않는다.
     */
    public void start(String sessionId, boolean inbound, Consumer<RiskAssessmentResponseDTO> sink) {
        CallState state = new CallState(inbound, sink);
        calls.put(sessionId, state);

        long interval = llmProperties.intervalMs();
        state.pending = scheduler.scheduleWithFixedDelay(
                () -> assess(sessionId), interval, interval, TimeUnit.MILLISECONDS);

        log.info("판정 세션 시작: {} (수신통화={}, 주기={}ms)", sessionId, inbound, interval);
    }

    /** 확정된 문장 하나가 도착했다. 누적만 하고 판정은 고정 주기 스케줄러가 돌린다. */
    public void onFinalTranscript(String sessionId, String transcript) {
        CallState state = calls.get(sessionId);
        if (state == null || transcript == null || transcript.isBlank()) {
            return;
        }
        synchronized (state) {
            state.lines.add(transcript);
        }
    }

    public void end(String sessionId) {
        CallState state = calls.remove(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.pending != null) {
                state.pending.cancel(false);
            }
        }
        log.info("판정 세션 종료: {}", sessionId);
    }

    private void assess(String sessionId) {
        CallState state = calls.get(sessionId);
        if (state == null) {
            return;
        }

        List<String> window;
        synchronized (state) {
            int from = Math.max(0, state.lines.size() - llmProperties.windowLines());
            window = List.copyOf(state.lines.subList(from, state.lines.size()));
        }
        if (window.isEmpty()) {
            return; // 아직 확정된 문장이 없다. 호출해봐야 레이트 리밋만 소모한다.
        }

        SignalDetectionResult detected = llmGatewayClient.detect(window);

        RiskAssessmentResponseDTO assessment;
        synchronized (state) {
            merge(state, detected);
            assessment = riskScorer.score(
                    Map.copyOf(state.stages),
                    Set.copyOf(state.negatives),
                    state.inbound,
                    List.copyOf(state.evidences.values()));
        }

        log.info("판정 결과 [{}] {}점 {} (체인 {}, 감점 {})",
                sessionId, assessment.score(), assessment.level(), assessment.chain(), assessment.penalty());
        state.sink.accept(assessment);
    }

    /** 래치 병합. 같은 신호가 다시 감지되면 더 높은 신뢰도와 그때의 근거로 갱신한다. */
    private void merge(CallState state, SignalDetectionResult detected) {
        for (SignalDetectionResult.Detected signal : detected.stages()) {
            double previous = state.stages.getOrDefault(signal.id(), 0.0);
            if (signal.confidence() > previous) {
                state.stages.put(signal.id(), signal.confidence());
                state.evidences.put(signal.id(),
                        new StageEvidenceResponseDTO(signal.id(), signal.confidence(), signal.evidence()));
            }
        }
        for (SignalDetectionResult.Detected signal : detected.negatives()) {
            state.negatives.add(signal.id());
            state.evidences.putIfAbsent(signal.id(),
                    new StageEvidenceResponseDTO(signal.id(), 1.0, signal.evidence()));
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 통화 하나의 누적 상태. 접근은 인스턴스 락으로 직렬화한다. */
    private static final class CallState {

        private final boolean inbound;
        private final Consumer<RiskAssessmentResponseDTO> sink;
        private final List<String> lines = new ArrayList<>();
        private final Map<String, Double> stages = new LinkedHashMap<>();
        private final Set<String> negatives = new java.util.LinkedHashSet<>();
        private final Map<String, StageEvidenceResponseDTO> evidences = new LinkedHashMap<>();
        private ScheduledFuture<?> pending;

        private CallState(boolean inbound, Consumer<RiskAssessmentResponseDTO> sink) {
            this.inbound = inbound;
            this.sink = sink;
        }
    }
}
