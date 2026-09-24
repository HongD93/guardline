package com.guardline.domain.call.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.guardline.domain.call.response.TranscriptEventResponseDTO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class AssemblyAiConnectionTest {
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final List<TranscriptEventResponseDTO> events = new CopyOnWriteArrayList<>();
    private AssemblyAiConnection connection;

    @BeforeEach
    void 준비() {
        when(session.isOpen()).thenReturn(true);
        connection = new AssemblyAiConnection(JsonMapper.builder().build(), events::add);
        connection.afterConnectionEstablished(session);
    }

    @Test
    void 종료요청_뒤_마지막전사를_받고_종료응답까지_기다린다() throws Exception {
        var completion = connection.terminate();
        assertThat(completion).isNotDone();
        verify(session, never()).close(any());
        connection.handleTextMessage(session, new TextMessage(
                "{\"type\":\"Turn\",\"end_of_turn\":true,\"transcript\":\"대표번호로 확인하세요\"}"));
        connection.handleTextMessage(session, new TextMessage("{\"type\":\"Termination\"}"));
        completion.get(1, TimeUnit.SECONDS);
        assertThat(events).extracting(TranscriptEventResponseDTO::type).containsExactly("final", "closed");
        verify(session).close(CloseStatus.NORMAL);
        connection.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(events).hasSize(2);
    }

    @Test
    void 중복종료와_종료후_오디오를_전송하지_않는다() throws Exception {
        var first = connection.terminate();
        assertThat(connection.terminate()).isSameAs(first);
        connection.sendAudio(ByteBuffer.allocate(1600));
        verify(session, times(1)).sendMessage(any());
        connection.afterConnectionClosed(session, CloseStatus.NORMAL);
        first.get(1, TimeUnit.SECONDS);
    }

    @Test
    void 종료응답이_없어도_제한시간후_연결을_정리한다() throws Exception {
        connection = new AssemblyAiConnection(JsonMapper.builder().build(), events::add, 25);
        connection.afterConnectionEstablished(session);
        connection.terminate().get(2, TimeUnit.SECONDS);
        verify(session).close(CloseStatus.NORMAL);
        assertThat(events).extracting(TranscriptEventResponseDTO::type).containsExactly("error", "closed");
        assertThat(events.getLast()).satisfies(event ->
                assertThat(event.message()).contains("시간 초과"));
    }

    @Test
    void 종료요청_전송실패도_완료와_정리를_보장한다() throws Exception {
        doThrow(new IOException("test transport failure")).when(session).sendMessage(any());
        connection.terminate().get(1, TimeUnit.SECONDS);
        verify(session).close(CloseStatus.NORMAL);
        assertThat(events).singleElement().satisfies(event ->
                assertThat(event.message()).contains("전송에 실패"));
    }

    @Test
    void 서버가_먼저_닫아도_종료_완료를_재사용한다() throws Exception {
        when(session.isOpen()).thenReturn(false);
        connection.afterConnectionClosed(session, CloseStatus.NORMAL);
        connection.terminate().get(1, TimeUnit.SECONDS);
        verify(session, never()).sendMessage(any());
        assertThat(events).hasSize(1);
    }
}
