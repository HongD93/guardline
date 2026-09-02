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
import org.springframework.web.client.HttpClientErrorException;
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
            S4 앱·정보 요구: 앱을 설치하거나 다운로드하라고 요구, 보낸 링크를 클릭하라고 유도,
               인증번호/OTP/비밀번호/카드번호를 알려달라고 요구
               (주의: 이미 있는 공식 앱에서 직접 신청·확인하라는 안내는 S4가 아니라 N2입니다)
            S5 돈 요구: 계좌번호를 알려주며 입금·송금·이체를 요청, 안전계좌·국가계좌 안내,
               현금 인출 후 전달 요구, 상품권·가상자산 구매 요구
            N1 상의 권유: 가족과 상의하라, 천천히 결정하라 (S3의 정반대)
            N2 공식 확인 유도: 앱에서 직접 확인하라, 대표번호로 다시 걸어라, 비밀번호는 묻지 않는다
            N4 안내로 종결: 통화 전체에 금전·계좌·이체·앱설치·개인정보 요구가 전혀 없고
               정보 안내만 하고 끝난 경우에만 yes. 그런 요구가 하나라도 있으면 반드시 no입니다.

            통화 내용의 각 줄 앞에 화자 라벨(A, B ...)이 붙어 있을 수 있습니다. 같은 문장이라도
            누가 말했는지에 따라 뜻이 정반대입니다. "가족에게 말하지 마세요"는 상대방이 말하면
            S3(입막음)이지만, 전화를 받은 사람이 "가족과 상의해볼게요"라고 말한 것은 S3이 아니라
            N1(상의 권유)입니다. 신호를 요구하거나 지시하는 쪽의 발화를 기준으로 판정하세요.

            중요: 당신은 사기 여부를 판단하지 않습니다. 신호가 통화에 나타났는지만 표시하세요.
            정상적인 은행·카드사·관공서 통화도 S1과 S2를 밟는 것이 정상입니다. 그런 경우에도
            신호가 있으면 반드시 yes로 표시하세요. 사기인지 아닌지는 다른 단계에서 결정합니다.

            각 항목마다 다음 한 줄로 답하세요. 다른 말은 쓰지 마세요.
            <항목ID>|<yes 또는 no>|<0.0~1.0 확신도>|<근거 문장 원문 그대로, 없으면 - >

            [예시 1] 사기 통화
            입력: 서울중앙지검 김민수 수사관입니다 / 명의도용 정황이 확인됐습니다 /
                  가족분께도 절대 말씀하시면 안 됩니다 / 국가안전계좌로 이체해 주시면
            출력:
            S1|yes|0.95|서울중앙지검 김민수 수사관입니다
            S2|yes|0.9|명의도용 정황이 확인됐습니다
            S3|yes|0.95|가족분께도 절대 말씀하시면 안 됩니다
            S4|no|0.0|-
            S5|yes|0.9|국가안전계좌로 이체해 주시면
            N1|no|0.0|-
            N2|no|0.0|-
            N4|no|0.0|-

            [예시 2] 정상 카드사 통화 - S1·S2를 밟지만 감점 신호가 함께 나온다
            입력: OO카드 이상거래탐지팀 김서연입니다 / 고객님 명의 카드로 해외 결제 시도가
                  있었습니다 / 앱에서 직접 신청하시거나 대표번호로 연락 주세요 /
                  천천히 확인해 보시고 진행하세요
            출력:
            S1|yes|0.9|OO카드 이상거래탐지팀 김서연입니다
            S2|yes|0.8|고객님 명의 카드로 해외 결제 시도가 있었습니다
            S3|no|0.0|-
            S4|no|0.0|-
            S5|no|0.0|-
            N1|yes|0.9|천천히 확인해 보시고 진행하세요
            N2|yes|0.9|앱에서 직접 신청하시거나 대표번호로 연락 주세요
            N4|no|0.0|-
            """;

    private static final Pattern LINE = Pattern.compile("^(S[1-5]|N[124])\\|(yes|no)\\|([\\d.]+)\\|(.*)$");

    /** 이 확신도 미만은 버린다. 소형 모델이 애매한 항목에 0.5를 주고 yes로 표시하는 경향이 있다. */
    private static final double MIN_CONFIDENCE = 0.6;

    /**
     * 429를 맞은 뒤 다음 호출까지 쉬는 시간.
     *
     * <p>무료 계정 리밋을 실측한 값이다 - 연속 버스트는 2회까지, 429 이후 회복까지 약 30초.
     * 10초 주기로 호출하면 절반이 429로 버려진다. 쉬는 동안에도 규칙 기반 감지는 매 회차
     * 돌기 때문에 화면 갱신이 멈추지는 않는다.
     */
    private static final long COOLDOWN_MS = 30_000;

    /** 쿨다운 해제 시각. 세션이 여러 개여도 리밋은 계정 단위라 인스턴스 하나로 관리한다. */
    private volatile long cooldownUntil = 0;

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
        long now = System.currentTimeMillis();
        if (now < cooldownUntil) {
            log.debug("레이트 리밋 쿨다운 중. {}ms 남음. 이번 회차는 규칙 감지만 쓴다.", cooldownUntil - now);
            return SignalDetectionResult.empty();
        }

        String userPrompt = "[통화 내용]\n" + String.join("\n", recentLines);

        Map<String, Object> body = Map.of(
                "model", llmProperties.model(),
                "max_tokens", llmProperties.maxTokens(),
                // 같은 통화를 반복 판정하므로 회차마다 결과가 흔들리면 게이지가 요동친다.
                "temperature", 0,
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
        } catch (HttpClientErrorException.TooManyRequests e) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
            log.info("레이트 리밋(429). {}초간 LLM 호출을 쉬고 규칙 감지로 버틴다.", COOLDOWN_MS / 1000);
            return SignalDetectionResult.empty();
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
