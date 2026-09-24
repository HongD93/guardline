package com.guardline.domain.call.handler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.guardline.domain.call.client.AssemblyAiConnection;
import com.guardline.domain.call.client.AssemblyAiStreamClient;
import com.guardline.domain.call.client.UpstreamListener;
import com.guardline.domain.call.response.QuotaResponseDTO;
import com.guardline.domain.call.response.TranscriptEventResponseDTO;
import com.guardline.domain.call.service.SessionQuota;
import com.guardline.domain.risk.service.RiskAssessmentService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class CallRelayHandlerTest {
    private final AssemblyAiStreamClient client = mock(AssemblyAiStreamClient.class);
    private final AssemblyAiConnection upstream = mock(AssemblyAiConnection.class);
    private final RiskAssessmentService risk = mock(RiskAssessmentService.class);
    private final SessionQuota quota = mock(SessionQuota.class);
    private final WebSocketSession browser = mock(WebSocketSession.class);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private CallRelayHandler handler;
    private UpstreamListener listener;

    @BeforeEach
    void 준비() throws Exception {
        when(browser.getId()).thenReturn("test-session");
        when(browser.isOpen()).thenReturn(true);
        when(quota.maxDurationMs()).thenReturn(60000L);
        when(quota.snapshot()).thenReturn(new QuotaResponseDTO(50, 50, 60));
        when(client.open(any())).thenReturn(upstream);
        when(upstream.terminate()).thenReturn(terminated);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(risk).end(anyString(), any());
        handler = new CallRelayHandler(client, JsonMapper.builder().build(), risk, quota);
        handler.afterConnectionEstablished(browser);
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"start\"}"));
        var capture = ArgumentCaptor.forClass(UpstreamListener.class);
        verify(client).open(capture.capture());
        listener = capture.getValue();
    }

    @AfterEach
    void 종료() {
        terminated.complete(null);
        handler.shutdown();
    }

    @Test
    void 마지막전사와_업스트림종료_뒤에만_최종판정과_한도반납을_수행한다() throws Exception {
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"stop\"}"));
        verify(risk, never()).end(anyString(), any());
        verify(quota, never()).release();
        verify(browser, never()).close(any());
        listener.onEvent(TranscriptEventResponseDTO.finalTurn("대표번호로 확인하세요", 1, "A"));
        listener.onEvent(TranscriptEventResponseDTO.closed("종료"));
        terminated.complete(null);
        var order = inOrder(risk, quota, browser);
        order.verify(risk).onFinalTranscript("test-session", "대표번호로 확인하세요", "A");
        order.verify(quota).release();
        order.verify(risk).end(eq("test-session"), any());
        order.verify(browser).close(CloseStatus.NORMAL);
    }

    @Test
    void 중복stop과_브라우저종료가_정리를_중복실행하지_않는다() {
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"stop\"}"));
        handler.handleTextMessage(browser, new TextMessage("{\"type\":\"stop\"}"));
        handler.afterConnectionClosed(browser, CloseStatus.NORMAL);
        terminated.complete(null);
        verify(upstream).terminate();
        verify(quota).release();
        verify(risk).end(eq("test-session"), any());
    }

    @Test
    void 업스트림이_먼저_종료돼도_한도를_반납한다() {
        listener.onEvent(TranscriptEventResponseDTO.closed("원격 종료"));
        terminated.complete(null);
        verify(upstream).terminate();
        verify(quota).release();
        verify(risk).end(eq("test-session"), any());
    }
}
