package com.guardline.domain.risk.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.guardline.global.config.AssemblyAiProperties;
import com.guardline.global.config.LlmProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class LlmGatewayClientTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "S4|링크 하나 보내드렸습니다",
            "S4|공식 앱에서 직접 확인하세요",
            "S4|이 앱을 설치하지 마세요",
            "S4|비밀번호를 알려줄 필요가 없습니다",
            "S5|계좌 알려주세요",
            "S5|네 지금 보낼게요",
            "S5|오십만 원을 보내지 마세요",
            "S5|계좌번호 확인했고 잔액은 오십만 원입니다",
            "S5|송금은 하지 마세요"
    })
    void 원문이어도_요청이_아닌_근거는_행동신호로_쓰지_않는다(String id, String evidence) {
        content = id + "|yes|0.95|" + evidence;
        assertThat(client.detect(List.of(evidence)).stages()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "S4|A: 앱을 설치해 주세요",
            "S4|보내드린 링크를 눌러 주세요",
            "S4|인증번호를 알려주세요",
            "S4|OTP를 불러 주세요",
            "S5|국가안전계좌로 전액 이체해 주시면 됩니다",
            "S5|오십만 원만 먼저 보내줄 수 있어?",
            "S5|계좌번호 123456 예금주 테스트입니다 십오만 삼천 원이요",
            "S5|현금을 인출해서 전달해 주세요",
            "S5|상품권을 구매해 주세요"
    })
    void 실제_설치와_정보와_송금요구_근거는_유지한다(String id, String evidence) {
        content = id + "|yes|0.9|" + evidence;
        assertThat(client.detect(List.of(evidence)).stages()).singleElement().satisfies(signal -> {
            assertThat(signal.id()).isEqualTo(id);
            assertThat(signal.evidence()).isEqualTo(evidence);
        });
    }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private HttpServer server;
    private LlmGatewayClient client;
    private volatile String content;
    private volatile int responseStatus = 200;

    @BeforeEach
    void 준비() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = mapper.writeValueAsString(Map.of("choices",
                        List.of(Map.of("message", Map.of("content", content)))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        client = new LlmGatewayClient(
                new AssemblyAiProperties("test-key", "", 16000, "", "", "", false, List.of()),
                new LlmProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-model", 512, 2000, 10000, 50), mapper);
    }

    @AfterEach
    void 종료() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 원문과_일치하는_위험신호와_감점신호를_유지한다() {
        content = "S1|yes|0.9|은행 상담원입니다\nN1|yes|0.9|가족과 상의하세요";
        SignalDetectionResult result = client.detect(List.of("은행 상담원입니다", "가족과 상의하세요"));
        assertThat(result.stages()).singleElement().satisfies(signal -> {
            assertThat(signal.id()).isEqualTo("S1");
            assertThat(signal.evidence()).isEqualTo("은행 상담원입니다");
        });
        assertThat(result.negatives()).singleElement().satisfies(signal ->
                assertThat(signal.evidence()).isEqualTo("가족과 상의하세요"));
    }

    @Test
    void 띄어쓰기와_화자표시_차이만_있으면_실제_원문으로_연결한다() {
        content = "N1|yes|0.9|가족과상의하세요";
        assertThat(client.detect(List.of("A: 가족과 상의하세요")).negatives())
                .singleElement().satisfies(signal ->
                        assertThat(signal.evidence()).isEqualTo("A: 가족과 상의하세요"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"가족과 상의하세요", "가족과 상의하지 마세요 천천히 결정하세요", "-", "", "안 해"})
    void 원문에_없는_근거나_짧게_잘라낸_근거는_감점에_사용하지_않는다(String evidence) {
        content = "N1|yes|0.95|" + evidence;
        assertThat(client.detect(List.of("가족과 상의하지 마세요", "상담은 안 해 드립니다"))
                .negatives()).isEmpty();
    }

    @Test
    void 원문에_없는_위험신호도_제외하고_다른_유효신호는_유지한다() {
        content = "S4|yes|0.9|앱을 설치하세요\nS3|yes|0.95|아무에게도 말하지 마세요";
        assertThat(client.detect(List.of("아무에게도 말하지 마세요")).stages())
                .extracting(SignalDetectionResult.Detected::id).containsExactly("S3");
    }

    @Test
    void 전사가_비어있으면_근거를_만들지_않는다() {
        content = "S5|yes|0.9|안전계좌로 이체하세요";
        assertThat(client.detect(List.of()).stages()).isEmpty();
    }

    @Test
    void 호출제한_응답후_남은_대기시간을_알린다() {
        content = "";
        responseStatus = 429;
        assertThat(client.retryAfterMillis()).isZero();
        client.detect(List.of("테스트 문장"));
        assertThat(client.retryAfterMillis()).isBetween(1L, 30_000L);
    }
}
