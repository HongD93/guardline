package com.guardline.domain.call.handler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.guardline.domain.call.client.AssemblyAiConnection;
import com.guardline.domain.call.client.AssemblyAiStreamClient;
import com.guardline.domain.call.response.TranscriptEventResponseDTO;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<String, WebSocketSession> browserSessions = new ConcurrentHashMap<>();
    private final Map<String, AssemblyAiConnection> upstreams = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // WebSocketSession은 동시 전송에 안전하지 않다. 업스트림 수신 스레드와 여기가 같이 쓰므로 감싼다.
        browserSessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        log.info("브라우저 연결: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            switch (node.path("type").asText()) {
                case "start" -> startUpstream(sessionId);
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
        stopUpstream(session.getId());
        browserSessions.remove(session.getId());
        log.info("브라우저 연결 종료: {} ({})", session.getId(), status.getCode());
    }

    private void startUpstream(String sessionId) throws Exception {
        if (upstreams.containsKey(sessionId)) {
            return;
        }
        AssemblyAiConnection connection = streamClient.open(event -> send(sessionId, event));
        upstreams.put(sessionId, connection);
    }

    private void stopUpstream(String sessionId) {
        AssemblyAiConnection upstream = upstreams.remove(sessionId);
        if (upstream != null) {
            upstream.terminate();
        }
    }

    private void send(String sessionId, TranscriptEventResponseDTO event) {
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
