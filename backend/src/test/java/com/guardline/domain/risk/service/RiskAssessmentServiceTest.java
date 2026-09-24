package com.guardline.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.guardline.domain.risk.client.LlmGatewayClient;
import com.guardline.domain.risk.client.SignalDetectionResult;
import com.guardline.domain.risk.response.RiskAssessmentResponseDTO;
import com.guardline.global.config.LlmProperties;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RiskAssessmentServiceTest {
    @Test
    void 최종호출에서_처음_429가_생겨도_한번만_재시도한다() throws Exception {
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        when(llm.retryAfterMillis()).thenReturn(0L, 25L, 25L);
        when(llm.detect(anyList())).thenReturn(SignalDetectionResult.empty());
        CountDownLatch ended = new CountDownLatch(1);
        RiskAssessmentService service = new RiskAssessmentService(llm, new RiskScorer(),
                new KeywordSignalDetector(), new LlmProperties("", "", 512, 1000, 60000, 60));
        try {
            service.start("test-session", true, ignored -> {});
            service.onFinalTranscript("test-session", "마지막 안내입니다", null);
            service.end("test-session", ended::countDown);
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            verify(llm, times(2)).detect(anyList());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void 마지막판정은_호출제한을_한번_기다린뒤_끝낸다() throws Exception {
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        when(llm.retryAfterMillis()).thenReturn(150L);
        when(llm.detect(anyList())).thenReturn(SignalDetectionResult.empty());
        CountDownLatch ended = new CountDownLatch(1);
        RiskAssessmentService service = new RiskAssessmentService(llm, new RiskScorer(),
                new KeywordSignalDetector(), new LlmProperties("", "", 512, 1000, 60000, 60));
        try {
            service.start("test-session", true, ignored -> {});
            service.onFinalTranscript("test-session", "마지막 안내입니다", null);
            service.end("test-session", ended::countDown);
            assertThat(ended.await(75, TimeUnit.MILLISECONDS)).isFalse();
            verify(llm, never()).detect(anyList());
            // 계속 대기 시간이 보고돼도 추가로 기다리지 않고 한 번 호출 후 종료한다.
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            verify(llm, times(1)).detect(anyList());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void 안내종결_이후_이체요구가_추가되면_이전_감점을_철회한다() throws Exception {
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        when(llm.detect(anyList())).thenReturn(SignalDetectionResult.empty());
        List<RiskAssessmentResponseDTO> results = new CopyOnWriteArrayList<>();
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        RiskAssessmentService service = new RiskAssessmentService(llm, new RiskScorer(),
                new KeywordSignalDetector(), new LlmProperties("", "", 512, 1000, 10, 60));
        try {
            service.start("test-session", true, result -> { results.add(result); first.countDown(); });
            service.onFinalTranscript("test-session", "추가로 진행하실 부분은 없습니다", null);
            assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(results.getFirst().negatives()).contains("N4");
            service.onFinalTranscript("test-session", "안전계좌로 이체하세요", null);
            service.end("test-session", ended::countDown);
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(results.getLast().negatives()).doesNotContain("N4");
            assertThat(results.getLast().penalty()).isZero();
        } finally {
            service.shutdown();
        }
    }

    @Test
    void 모델이_안부인사를_감점으로_분류해도_점수를_낮추지_않는다() throws Exception {
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        when(llm.detect(anyList())).thenReturn(new SignalDetectionResult(List.of(
                new SignalDetectionResult.Detected("S3", 0.9, "오늘 안에 소명하지 않으시면")), List.of(
                new SignalDetectionResult.Detected("N1", 0.95, "아들 지금 통화 괜찮아"),
                new SignalDetectionResult.Detected("N4", 0.95, "아들 지금 통화 괜찮아"))));
        List<RiskAssessmentResponseDTO> results = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        RiskAssessmentService service = new RiskAssessmentService(llm, new RiskScorer(),
                new KeywordSignalDetector(), new LlmProperties("", "", 512, 1000, 60000, 60));
        try {
            service.start("test-session", true, results::add);
            service.onFinalTranscript("test-session", "아들 지금 통화 괜찮아", null);
            service.onFinalTranscript("test-session", "오늘 안에 소명하지 않으시면", null);
            service.end("test-session", ended::countDown);
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(results).singleElement().satisfies(result -> {
                assertThat(result.penalty()).isZero();
                assertThat(result.negatives()).isEmpty();
                assertThat(result.isolationFloor()).isFalse();
                assertThat(result.evidences()).noneSatisfy(evidence ->
                        assertThat(evidence.id()).isEqualTo("S3"));
            });
        } finally {
            service.shutdown();
        }
    }

    @Test
    void 진행중인_판정_다음에_마지막전사를_판정하고_종료한다() throws Exception {
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        List<RiskAssessmentResponseDTO> results = new CopyOnWriteArrayList<>();
        when(llm.detect(anyList())).thenAnswer(invocation -> {
            if (count.incrementAndGet() == 1) {
                entered.countDown();
                assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            }
            return SignalDetectionResult.empty();
        });
        RiskAssessmentService service = new RiskAssessmentService(llm, new RiskScorer(),
                new KeywordSignalDetector(), new LlmProperties("", "", 512, 1000, 10, 60));
        try {
            service.start("test-session", true, results::add);
            service.onFinalTranscript("test-session", "안녕하세요", null);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            service.onFinalTranscript("test-session", "수사 기밀이니 발설하지 마세요", null);
            service.end("test-session", ended::countDown);
            assertThat(ended.await(100, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(ended.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(results).hasSize(2);
            assertThat(results.getLast().score()).isGreaterThanOrEqualTo(80);
            assertThat(results.getLast().evidences()).anySatisfy(evidence ->
                    assertThat(evidence.id()).isEqualTo("S3"));
        } finally {
            release.countDown();
            service.shutdown();
        }
    }
}
