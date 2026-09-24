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
    private final KeywordSignalDetector keywordSignalDetector;
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

    /**
     * 확정된 문장 하나가 도착했다. 누적만 하고 판정은 고정 주기 스케줄러가 돌린다.
     *
     * <p>화자 라벨을 문장 앞에 붙인다. "가족에게 말하지 마라"는 상대방이 말해야 격리 유도이고,
     * 사용자가 "가족과 상의해볼게요"라고 한 것은 정반대 신호다. 누가 말했는지 없이는 같은
     * 문장을 구별할 수 없다.
     */
    public void onFinalTranscript(String sessionId, String transcript, String speaker) {
        CallState state = calls.get(sessionId);
        if (state == null || transcript == null || transcript.isBlank()) {
            return;
        }
        String line = (speaker == null || speaker.isBlank()) ? transcript : speaker + ": " + transcript;
        synchronized (state) {
            state.lines.add(line);
        }
    }

    /**
     * 통화 종료. 주기 작업을 멈추기 전에 마지막 판정을 한 번 더 돌린다.
     *
     * <p>통화 막바지 문장이 주기 사이에 들어오면 반영되지 않은 채 끝난다. 감점 신호는 보통
     * 통화 끝에 나오므로(정상 상담원이 마지막에 "공식 앱에서 확인하세요"라고 안내), 이 마지막
     * 판정이 없으면 정상 통화가 주의 등급으로 끝나버린다.
     */
    public void end(String sessionId, Runnable onComplete) {
        CallState state = calls.get(sessionId);
        if (state == null) {
            onComplete.run();
            return;
        }
        synchronized (state) {
            if (state.pending != null) {
                state.pending.cancel(false);
            }
        }
        // 판정은 LLM 호출이라 오래 걸린다. 호출 스레드(WebSocket 종료 처리)를 막지 않는다.
        scheduler.execute(() -> finishAssessment(sessionId, state, onComplete, true));
    }

    private void finishAssessment(String sessionId, CallState state, Runnable onComplete, boolean mayWait) {
        synchronized (state.assessmentLock) {
            long retryAfter = llmGatewayClient.retryAfterMillis();
            if (mayWait && retryAfter > 0) {
                // 스케줄러 스레드를 sleep으로 점유하지 않는다. 다른 통화가 다시 429를
                // 받더라도 한 번만 기다리므로 종료 대기가 무한히 늘어나지 않는다.
                scheduler.schedule(() -> finishAssessment(sessionId, state, onComplete, false),
                        Math.min(retryAfter, 30_000) + 50, TimeUnit.MILLISECONDS);
                return;
            }
            boolean deferred = false;
            try {
                assess(sessionId);
                // 종료 시도 자체가 처음 429를 받을 수도 있다.
                long newlyLimited = llmGatewayClient.retryAfterMillis();
                if (mayWait && newlyLimited > 0) {
                    scheduler.schedule(() -> finishAssessment(sessionId, state, onComplete, false),
                            Math.min(newlyLimited, 30_000) + 50, TimeUnit.MILLISECONDS);
                    deferred = true;
                }
            } finally {
                if (!deferred) {
                    calls.remove(sessionId, state);
                    log.info("판정 세션 종료: {}", sessionId);
                    onComplete.run();
                }
            }
        }
    }

    private void assess(String sessionId) {
        CallState state = calls.get(sessionId);
        if (state == null) {
            return;
        }
        // 종료 직전 주기 판정이 아직 응답을 기다릴 수 있다. 최종 판정이 먼저 끝나고
        // 옛 결과가 뒤늦게 전송되지 않도록 통화별로 판정을 직렬화한다.
        synchronized (state.assessmentLock) {
            if (calls.get(sessionId) != state) {
                return;
            }
            assessState(sessionId, state);
        }
    }

    private void assessState(String sessionId, CallState state) {
        List<String> window;
        synchronized (state) {
            int from = Math.max(0, state.lines.size() - llmProperties.windowLines());
            window = List.copyOf(state.lines.subList(from, state.lines.size()));
        }
        if (window.isEmpty()) {
            return; // 아직 확정된 문장이 없다. 호출해봐야 레이트 리밋만 소모한다.
        }

        // 규칙이 먼저다. LLM 호출이 실패하거나(429·타임아웃) 회차마다 결과가 흔들려도
        // 어휘로 확정되는 신호는 항상 잡힌다.
        SignalDetectionResult byRule = keywordSignalDetector.detect(window);
        SignalDetectionResult byLlm = llmGatewayClient.detect(window);

        RiskAssessmentResponseDTO assessment;
        synchronized (state) {
            // 감점은 새 대화에 의해 반박될 수 있다. 특히 안내 종결 뒤에 이체 요구가
            // 추가되면 앞선 N4를 계속 유지해서는 안 된다.
            state.negatives.clear();
            state.evidences.keySet().removeIf(id -> id.startsWith("N"));
            merge(state, byRule);
            // 원문을 정확히 인용해도 모델이 안부 인사를 상의 권유로 오분류할 수 있다.
            // 위험을 낮추는 근거는 명시적인 안내 문구가 확인된 규칙 결과만 사용한다.
            // S3는 단독으로 위험 경고를 켜므로 시간 압박 같은 모델 오분류로 발동하지 않는다.
            merge(state, new SignalDetectionResult(byLlm.stages().stream()
                    .filter(signal -> !"S3".equals(signal.id())).toList(), List.of()));
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

        private final Object assessmentLock = new Object();
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
