package com.guardline.domain.risk.client;

import com.guardline.global.config.AssemblyAiProperties;
import com.guardline.global.config.LlmProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AssemblyAI LLM Gateway로 통화 트랜스크립트에서 보이스피싱 신호를 감지한다.
 *
 * <p>STT와 같은 API 키를 쓴다. 이 클래스는 신호 감지까지만 하고 점수는 내지 않는다.
 *
 * <p>출력 형식은 JSON 스키마가 아니라 줄 단위 체크리스트다. 무료 크레딧 계정에서 쓸 수 있는
 * 모델이 response_format을 지원하지 않고, 열린 형태로 신호를 추출하게 하면 소형 모델이
 * 대부분의 단계를 놓친다. 8개 항목을 하나씩 예/아니오로 묻는 방식은 같은 모델에서
 * S1~S5를 전부 잡아낸다.
 */
@Slf4j
@Component
public class LlmGatewayClient {

    private static final String SYSTEM_PROMPT = """
            당신은 한국어 통화에서 보이스피싱 신호를 판정하는 검사기입니다.
            아래 8개 항목을 하나씩 차례로 검사해, 통화 내용에 그 신호가 있는지 판정하세요.
            항목을 건너뛰지 말고 8개 전부에 대해 답하세요.

            S1 기관 사칭: 검찰/경찰/금융감독원/국세청/은행/카드사/보험사/통신사/관공서 소속이라고
               밝힘, 수사관·상담원·팀 이름을 대며 자기소개, 사건번호나 접수번호 언급
            S2 공포 조성: 명의도용, 내 명의로 개설된 계좌·카드, 내가 하지 않은 결제·거래 통보,
               대포통장/자금세탁 연루, 계좌동결, 체포영장, 공범 취급, "오늘 안에" 같은 시간 압박
            S3 입막음: 아무에게도 말하지 말라, 수사 기밀이니 발설 금지, 전화 끊지 말라, 혼자 있는 곳으로 가라
            S4 앱·정보 요구: 앱 설치 요구, 링크 클릭 유도, 인증번호/비밀번호 요구
            S5 돈 요구: 계좌이체 지시, 안전계좌 안내, 현금 인출 전달, 상품권 구매
            N1 상의 권유: 가족과 상의하라, 천천히 결정하라 (S3의 정반대)
            N2 공식 확인 유도: 앱에서 직접 확인하라, 대표번호로 다시 걸어라, 비밀번호는 묻지 않는다
            N4 안내로 종결: 돈이나 계좌 얘기 없이 안내만 하고 끝남

            중요: 당신은 사기 여부를 판단하지 않습니다. 신호가 통화에 나타났는지만 표시하세요.
            정상적인 은행·카드사·관공서 통화도 S1과 S2를 밟는 것이 정상입니다. 그런 경우에도
            신호가 있으면 반드시 yes로 표시하세요. 사기인지 아닌지는 다른 단계에서 결정합니다.

            각 항목마다 다음 한 줄로 답하세요. 다른 말은 쓰지 마세요.
            <항목ID>|<yes 또는 no>|<0.0~1.0 확신도>|<근거 문장 원문 그대로, 없으면 - >

            예시:
            S1|yes|0.9|서울중앙지검 김민수 수사관입니다
            S2|no|0.0|-
            """;

    private static final Pattern LINE = Pattern.compile("^(S[1-5]|N[124])\\|(yes|no)\\|([\\d.]+)\\|(.*)$");

    /** 이 확신도 미만은 버린다. 소형 모델이 애매한 항목에 0.5를 주고 yes로 표시하는 경향이 있다. */
    private static final double MIN_CONFIDENCE = 0.6;

    private final AssemblyAiProperties assemblyAiProperties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LlmGatewayClient(
            AssemblyAiProperties assemblyAiProperties,
            LlmProperties llmProperties,
            ObjectMapper objectMapper
    ) {
        this.assemblyAiProperties = assemblyAiProperties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;

        // 통화 중 실시간 판정이라 무한 대기를 두면 안 된다. 느려지면 그 회차를 버리는 편이 낫다.
        Duration timeout = Duration.ofMillis(llmProperties.timeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(llmProperties.endpoint())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 매번 통화 전체를 다시 검사한다.
     *
     * <p>"이미 감지된 항목"을 알려주면 소형 모델이 나머지를 보고할 필요가 없다고 판단해 S1만
     * 잡고 끝내버린다. 신호는 어차피 서비스 계층에서 래치하므로 중복 보고는 문제가 되지 않는다.
     *
     * @param recentLines 판정에 넘길 확정 문장들
     * @return 감지 실패 시 빈 결과. 판정 한 회차를 건너뛸 뿐 통화 감시는 계속돼야 한다.
     */
    public SignalDetectionResult detect(List<String> recentLines) {
        String userPrompt = "[통화 내용]\n" + String.join("\n", recentLines);

        Map<String, Object> body = Map.of(
                "model", llmProperties.model(),
                "max_tokens", llmProperties.maxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            String response = restClient.post()
                    .header(HttpHeaders.AUTHORIZATION, assemblyAiProperties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parse(response, recentLines);
        } catch (Exception e) {
            // 한 회차를 건너뛸 뿐 통화 감시는 계속돼야 한다. 모델 ID 오타처럼 매번 실패하는
            // 설정 문제를 바로 알아볼 수 있도록 모델명을 함께 남긴다.
            log.warn("신호 감지 실패 (model={}). 이번 회차는 건너뛴다.", llmProperties.model(), e);
            return SignalDetectionResult.empty();
        }
    }

    private SignalDetectionResult parse(String response, List<String> transcript) {
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").path(0).path("message").path("content").asString();

        if (content == null || content.isBlank()) {
            log.warn("LLM 응답에 content가 없다: {}", response);
            return SignalDetectionResult.empty();
        }

        List<SignalDetectionResult.Detected> stages = new ArrayList<>();
        List<SignalDetectionResult.Detected> negatives = new ArrayList<>();

        for (String rawLine : content.split("\n")) {
            Matcher matcher = LINE.matcher(rawLine.trim());
            if (!matcher.matches() || !"yes".equals(matcher.group(2))) {
                continue;
            }

            double confidence = parseConfidence(matcher.group(3));
            if (confidence < MIN_CONFIDENCE) {
                continue;
            }

            String id = matcher.group(1);
            // 모델이 근거에 없는 말을 덧붙이는 경우가 있어 실제 전사 문장으로 되돌린다.
            String evidence = snapToTranscript(matcher.group(4), transcript);

            SignalDetectionResult.Detected signal = new SignalDetectionResult.Detected(id, confidence, evidence);
            if (id.startsWith("S")) {
                stages.add(signal);
            } else {
                negatives.add(signal);
            }
        }
        return new SignalDetectionResult(stages, negatives);
    }

    private double parseConfidence(String raw) {
        try {
            return Math.clamp(Double.parseDouble(raw), 0.0, 1.0);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 모델이 돌려준 근거를 실제 전사 문장 중 가장 겹치는 것으로 바꾼다.
     *
     * <p>화면에서 근거 문장을 하이라이트해야 하는데, 모델이 지어낸 표현이 섞이면 원문에서 찾을 수
     * 없다. 2글자 조각의 겹침으로 가장 가까운 문장을 고른다.
     */
    private String snapToTranscript(String evidence, List<String> transcript) {
        String cleaned = evidence.trim();
        if (cleaned.isEmpty() || "-".equals(cleaned) || transcript.isEmpty()) {
            return cleaned;
        }

        String normalized = cleaned.replaceAll("\\s+", "");
        String best = cleaned;
        int bestScore = 0;

        for (String line : transcript) {
            String candidate = line.replaceAll("\\s+", "");
            int score = 0;
            for (int i = 0; i + 2 <= normalized.length(); i += 1) {
                if (candidate.contains(normalized.substring(i, i + 2))) {
                    score += 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = line;
            }
        }
        return best;
    }
}
