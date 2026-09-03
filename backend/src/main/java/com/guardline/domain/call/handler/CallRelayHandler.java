package com.guardline.domain.call.handler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.guardline.domain.call.client.AssemblyAiConnection;
import com.guardline.domain.call.client.AssemblyAiStreamClient;
import com.guardline.domain.call.response.RiskEventResponseDTO;
import com.guardline.domain.call.response.TranscriptEventResponseDTO;
import com.guardline.domain.call.service.SessionQuota;
import com.guardline.domain.risk.service.RiskAssessmentService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * 브라우저 <-> AssemblyAI 릴레이.
 *
 * <p>브라우저는 먼저 {@code {"type":"start"}} 텍스트 프레임을 보내고, 이후 16kHz mono PCM16
 * 바이너리 프레임을 흘려보낸다. {@code {"type":"stop"}}이 오거나 연결이 끊기면 업스트림을 정리한다.
 * 오디오가 서버를 거치므로 API 키가 브라우저에 노출되지 않고, 확정된 전사가 곧바로 서버에 남아
 * 판정 엔진에 그대로 넘길 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallRelayHandler extends AbstractWebSocketHandler {

    /** 브라우저로 내려보낼 때의 전송 대기 한도. 느린 클라이언트가 서버 메모리를 잡아먹지 않게 한다. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final AssemblyAiStreamClient streamClient;
    private final ObjectMapper objectMapper;
    private final RiskAssessmentService riskAssessmentService;
    private final SessionQuota sessionQuota;

    private final Map<String, WebSocketSession> browserSessions = new ConcurrentHashMap<>();
    private final Map<String, AssemblyAiConnection> upstreams = new ConcurrentHashMap<>();

    /** 상한을 점유한 세션. 중복 반납을 막기 위해 따로 들고 있는다. */
    private final Set<String> quotaHolders = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledFuture<?>> expiries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService expiryScheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // WebSocketSession은 동시 전송에 안전하지 않다. 업스트림 수신 스레드와 여기가 같이 쓰므로 감싼다.
        browserSessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        log.info("브라우저 연결: {}", session.getId());

        // 남은 체험 횟수를 바로 알려준다. 제한이 있다는 사실을 화면에서 감추지 않는다.
        send(session.getId(), sessionQuota.snapshot());
    }

    @PreDestroy
    void shutdown() {
        expiryScheduler.shutdownNow();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            switch (node.path("type").asText()) {
                // inbound는 텍스트가 아닌 통화 메타데이터다. 사용자가 건 전화면 감점 신호 N3이 붙는다.
                case "start" -> startUpstream(sessionId, node.path("inbound").asBoolean(true));
                case "stop" -> stopUpstream(sessionId);
                default -> log.debug("처리하지 않는 제어 메시지: {}", message.getPayload());
            }
        } catch (Exception e) {
            log.error("제어 메시지 처리 실패: {}", message.getPayload(), e);
            send(sessionId, TranscriptEventResponseDTO.error(e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AssemblyAiConnection upstream = upstreams.get(session.getId());
        if (upstream == null) {
            return; // start 이전에 도착한 오디오는 버린다
        }
        try {
            upstream.sendAudio(message.getPayload());
        } catch (IOException e) {
            log.error("업스트림 오디오 전송 실패", e);
            send(session.getId(), TranscriptEventResponseDTO.error("오디오 전송 실패: " + e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 이미 stopUpstream을 거쳤으면 세션이 없으므로 그대로 통과한다.
        stopUpstream(session.getId());
        browserSessions.remove(session.getId());
        log.info("브라우저 연결 종료: {} ({})", session.getId(), status.getCode());
    }

    private void startUpstream(String sessionId, boolean inbound) throws Exception {
        if (upstreams.containsKey(sessionId)) {
            return;
        }

        // 공개 배포에서는 재생 한 번이 곧 API 크레딧이다. 상한을 넘으면 이유를 알려주고 끝낸다.
        String denied = sessionQuota.acquire();
        if (denied != null) {
            log.info("세션 거절: {} - {}", sessionId, denied);
            send(sessionId, TranscriptEventResponseDTO.error(denied));
            return;
        }
        quotaHolders.add(sessionId);

        // 세션이 무한정 열려 있으면 크레딧이 새므로 최대 길이를 넘기면 서버가 끊는다.
        expiries.put(sessionId, expiryScheduler.schedule(() -> {
            log.info("세션 최대 길이 초과로 종료: {}", sessionId);
            send(sessionId, TranscriptEventResponseDTO.closed("체험 시간(%d초)이 끝났습니다."
                    .formatted(sessionQuota.maxDurationMs() / 1000)));
            stopUpstream(sessionId);
        }, sessionQuota.maxDurationMs(), TimeUnit.MILLISECONDS));

        riskAssessmentService.start(sessionId, inbound,
                assessment -> send(sessionId, RiskEventResponseDTO.of(assessment)));

        try {
            AssemblyAiConnection connection = streamClient.open(event -> {
                send(sessionId, event);
                // 확정 문장만 판정에 넘긴다. partial은 계속 바뀌므로 신호 감지에 쓸 수 없다.
                if ("final".equals(event.type())) {
                    riskAssessmentService.onFinalTranscript(sessionId, event.transcript(), event.speaker());
                }
            });
            upstreams.put(sessionId, connection);
        } catch (Exception e) {
            // 업스트림 연결에 실패하면 세션이 시작되지 않았으므로 상한을 되돌린다.
            // 브라우저가 소켓을 닫아줄 때까지 기다리면 실패한 시도가 한도를 먹는다.
            stopUpstream(sessionId);
            throw e;
        }
    }

    /**
     * 업스트림을 끊고 마지막 판정까지 마친 뒤 브라우저 세션을 닫는다.
     *
     * <p>브라우저가 stop을 보내자마자 소켓을 닫아버리면 마지막 판정 결과가 도착할 곳이 없다.
     * 감점 신호는 보통 통화 끝에 나오므로 그 결과를 놓치면 정상 통화가 주의 등급으로 끝난다.
     */
    private void stopUpstream(String sessionId) {
        ScheduledFuture<?> expiry = expiries.remove(sessionId);
        if (expiry != null) {
            expiry.cancel(false);
        }
        if (quotaHolders.remove(sessionId)) {
            sessionQuota.release();
        }

        AssemblyAiConnection upstream = upstreams.remove(sessionId);
        if (upstream != null) {
            upstream.terminate();
        }
        riskAssessmentService.end(sessionId, () -> closeBrowserSession(sessionId));
    }

    private void closeBrowserSession(String sessionId) {
        WebSocketSession session = browserSessions.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException e) {
            log.warn("브라우저 세션 종료 실패: {}", sessionId, e);
        }
    }

    private void send(String sessionId, Object event) {
        WebSocketSession session = browserSessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.warn("브라우저 전송 실패: {}", sessionId, e);
        }
    }
}
