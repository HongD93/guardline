package com.guardline.domain.call.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.guardline.domain.call.response.TranscriptEventResponseDTO;
import java.io.IOException;
import java.nio.ByteBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * AssemblyAI 실시간 스트리밍과의 연결 하나를 담당한다.
 *
 * <p>업스트림이 보내는 메시지 타입은 Begin / Turn / Termination 세 가지다. Turn은 같은 발화에 대해
 * 여러 번 날아오며 end_of_turn이 true인 것만 확정된 문장이다. 판정 엔진은 확정 문장만 소비하면 되지만,
 * 화면 자막은 partial까지 보여줘야 실시간처럼 보인다.
 */
@Slf4j
public class AssemblyAiConnection extends AbstractWebSocketHandler {

    private static final TextMessage TERMINATE = new TextMessage("{\"type\":\"Terminate\"}");

    private final ObjectMapper objectMapper;
    private final UpstreamListener listener;
    private volatile WebSocketSession session;

    AssemblyAiConnection(ObjectMapper objectMapper, UpstreamListener listener) {
        this.objectMapper = objectMapper;
        this.listener = listener;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        this.session = session;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();

            switch (type) {
                case "Begin" -> listener.onEvent(TranscriptEventResponseDTO.ready());
                case "Turn" -> handleTurn(node);
                case "Termination" -> listener.onEvent(
                        TranscriptEventResponseDTO.closed("업스트림 세션 종료 (오디오 %.1f초)"
                                .formatted(node.path("audio_duration_seconds").asDouble())));
                default -> log.debug("처리하지 않는 업스트림 메시지 타입: {}", type);
            }
        } catch (Exception e) {
            log.warn("업스트림 메시지 파싱 실패: {}", message.getPayload(), e);
        }
    }

    private void handleTurn(JsonNode node) {
        String transcript = node.path("transcript").asText("");
        if (transcript.isBlank()) {
            return;
        }
        Integer turnOrder = node.hasNonNull("turn_order") ? node.get("turn_order").asInt() : null;
        String speaker = readSpeaker(node);

        listener.onEvent(node.path("end_of_turn").asBoolean(false)
                ? TranscriptEventResponseDTO.finalTurn(transcript, turnOrder, speaker)
                : TranscriptEventResponseDTO.partial(transcript, turnOrder, speaker));
    }

    /**
     * 화자 라벨을 꺼낸다. 턴 단위 필드가 없으면 단어 단위 라벨의 첫 값을 쓴다.
     * 응답 형태가 모델마다 달라 두 위치를 모두 확인한다.
     */
    private String readSpeaker(JsonNode node) {
        if (node.hasNonNull("speaker")) {
            return node.get("speaker").asText();
        }
        for (JsonNode word : node.path("words")) {
            if (word.hasNonNull("speaker")) {
                return word.get("speaker").asText();
            }
        }
        return null;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("업스트림 전송 오류", exception);
        listener.onEvent(TranscriptEventResponseDTO.error("업스트림 전송 오류: " + exception.getMessage()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String reason = describeCloseCode(status.getCode());
        log.info("업스트림 연결 종료: {} {} ({})", status.getCode(), status.getReason(), reason);
        listener.onEvent(TranscriptEventResponseDTO.closed("업스트림 연결 종료 (%d) - %s"
                .formatted(status.getCode(), reason)));
    }

    /** 공식 지침이 정의한 종료 코드. 원인을 바로 알 수 있어야 디버깅에서 헤매지 않는다. */
    private static String describeCloseCode(int code) {
        return switch (code) {
            case 1000 -> "정상 종료";
            case 1008 -> "인증 실패 - API 키 또는 토큰 확인";
            case 3005 -> "서버가 세션을 취소함";
            case 3006 -> "잘못된 메시지 타입 또는 JSON";
            case 3007 -> "오디오 청크가 50~1000ms 범위를 벗어났거나 실시간보다 빠르게 전송됨";
            case 3008 -> "세션 3시간 상한 도달";
            case 3009 -> "동시 세션 수 초과";
            default -> "정의되지 않은 종료 코드";
        };
    }

    /** PCM16 오디오 청크를 업스트림으로 넘긴다. */
    public void sendAudio(ByteBuffer audio) throws IOException {
        WebSocketSession current = session;
        if (current != null && current.isOpen()) {
            current.sendMessage(new BinaryMessage(audio));
        }
    }

    /** 마지막 턴을 확정시키고 세션을 닫는다. */
    public void terminate() {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            current.sendMessage(TERMINATE);
            current.close(CloseStatus.NORMAL);
        } catch (IOException e) {
            log.warn("업스트림 종료 실패", e);
        }
    }
}
