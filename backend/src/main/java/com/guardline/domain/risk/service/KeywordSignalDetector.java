package com.guardline.domain.risk.service;

import com.guardline.domain.risk.client.SignalDetectionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 판단이 아니라 어휘로 확정되는 신호를 규칙으로 잡는다.
 *
 * <p>무료 크레딧 계정에서 쓸 수 있는 4B 모델은 회차마다 감지 결과가 흔들린다. "가족분께도 절대
 * 말씀하시면 안 됩니다" 같은 문장은 판단이 필요 없는 고정 패턴인데 이걸 모델의 변덕에 맡길
 * 이유가 없다. 규칙은 신호를 추가만 하고 제거하지 않으며, LLM 결과와 합집합으로 병합된다.
 *
 * <p>고정 문구를 우선 확인한다. 규칙도 오탐·누락이 가능하므로 반증 사례와 실제 전사를 검증한다.
 * <ul>
 *   <li>감점 신호(N1·N2·N4) - 오탐이 위험을 숨길 수 있어 명시적인 안내와 부정 표현을 검사한다.</li>
 *   <li>S3(격리 유도) - 정상 통화에 거의 나오지 않는 고정밀 패턴이고 위험 등급 floor를
 *       트리거하는 제품의 핵심이다.</li>
 *   <li>S1(기관 사칭)·S2(공포 조성) - 정상 통화도 정당하게 밟는 단계다. 둘만으로는 체인
 *       2단계에 그쳐 "주의"까지고 위험 등급은 S3 이상이 필요하므로 규칙으로 잡아도 안전하다.
 *       오히려 정상 통화에서 이 신호가 보여야 "같은 신호를 밟지만 감점이 판별한다"는 판정
 *       근거가 화면에 드러난다.</li>
 * </ul>
 * S4·S5는 뺐다. 앱·계좌·이체는 정상 통화에도 흔한 표현이라 규칙으로 잡으면 S1·S2와 합쳐져
 * 체인이 늘고 위험 등급까지 밀릴 수 있다. 그쪽은 LLM 판단에 맡긴다.
 */
@Component
public class KeywordSignalDetector {

    /** 규칙으로 확정한 신호의 신뢰도. S3 단독 트리거(0.8) 조건을 넘겨야 한다. */
    private static final double RULE_CONFIDENCE = 0.95;

    private static final Map<String, List<Pattern>> RULES = new LinkedHashMap<>();
    private static final Pattern NEGATIVE_DENIAL = compile(
            "(상의|의논|확인|연락|전화|신청|조회).{0,12}(하지\\s*마|하시면\\s*안|하면\\s*안|금지|필요.{0,4}없)");
    private static final Pattern INFORMATION_ONLY_EXCLUSION = compile(
            "금전|계좌|이체|송금|입금|현금|앱\\s*설치|다운로드|개인정보|비밀번호|인증번호|카드번호|OTP");

    static {
        // S1·S2는 정상 통화도 정당하게 밟는 단계다. 규칙으로 잡아도 위험 쪽으로 튀지 않는 이유는
        // 둘만으로는 체인 2단계에 그쳐 "주의"까지고, 위험 등급은 S3 이상이 필요하기 때문이다.
        // 오히려 정상 통화에서 이 신호가 화면에 보여야 "같은 신호를 밟지만 감점이 판별한다"는
        // 판정 근거가 드러난다.
        RULES.put("S1", List.of(
                compile("(지검|지방검찰청|검찰청|경찰청|경찰서|금융감독원|국세청|우체국)"),
                compile("(수사관|검사|조사관)(입니다|이라고|입니다만)"),
                compile("(고객센터|상담원|상담사|이상거래탐지팀|고객보호팀)"),
                compile("사건\\s*번호|접수\\s*번호")
        ));

        RULES.put("S2", List.of(
                compile("대포\\s*통장|자금\\s*세탁|명의\\s*도용"),
                compile("체포\\s*영장|구속\\s*영장|출국\\s*금지"),
                compile("공범(으로)?\\s*(간주|취급|입건)"),
                compile("계좌(가|를)?\\s*(동결|정지)"),
                compile("피의자\\s*신분")
        ));

        RULES.put("S3", List.of(
                // 발설 금지
                compile("(말씀|말)\\s*(하시)?(면|지)\\s*안\\s*(됩니다|돼|되)"),
                compile("발설"),
                compile("아무(에게|한테|도)\\s*(도)?\\s*(말|알리)"),
                compile("수사\\s*기밀|대외비"),
                // 통화 유지 강요. "통화는", "전화를"처럼 조사가 바뀌므로 한 글자 조사를 허용한다.
                compile("(전화|통화)[는은를이]?\\s*(절대)?\\s*끊(지|으시)\\s*(마|말)"),
                // 격리 이동 지시
                compile("(조용한|혼자.{0,4}있는)\\s*(곳|장소)")
        ));

        RULES.put("N1", List.of(
                compile("(가족|보호자|자녀|따님|아드님)(분)?(들)?(과|하고|이랑|께)?\\s*(도)?\\s*(함께)?\\s*(상의|의논|확인)"),
                compile("천천히\\s*(확인|결정|생각|알아)"),
                compile("(지금)?\\s*바로\\s*결정(하실|할)?\\s*(필요|이유)(는)?\\s*(전혀)?\\s*없"),
                compile("서두르지\\s*(마|않)")
        ));

        RULES.put("N2", List.of(
                compile("(공식)?\\s*(앱|홈페이지|어플)에서\\s*(직접)?\\s*(확인|신청|조회)"),
                compile("대표\\s*번호.{0,20}(전화|연락|확인|문의|다시|재통화)"),
                compile("(고객센터|콜센터)(로)?\\s*다시"),
                compile("(비밀번호|카드번호|인증번호).{0,12}(여쭤|묻|물어).{0,16}없")
        ));

        RULES.put("N4", List.of(
                compile("별도로\\s*(방문|진행)(하실)?\\s*(필요|일)(는)?\\s*없"),
                compile("추가로\\s*진행(하실)?\\s*(부분|것)(은)?\\s*없")
        ));
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex);
    }

    /**
     * @param lines 확정된 전사 문장들
     * @return 규칙으로 확정된 신호. 근거는 매칭된 문장 원문 그대로다.
     */
    public SignalDetectionResult detect(List<String> lines) {
        List<SignalDetectionResult.Detected> stages = new ArrayList<>();
        List<SignalDetectionResult.Detected> negatives = new ArrayList<>();

        for (Map.Entry<String, List<Pattern>> rule : RULES.entrySet()) {
            if ("N4".equals(rule.getKey()) && lines.stream()
                    .anyMatch(line -> INFORMATION_ONLY_EXCLUSION.matcher(line).find())) {
                continue;
            }
            String matched = firstMatch(lines, rule.getValue(), rule.getKey().startsWith("N"));
            if (matched == null) {
                continue;
            }
            SignalDetectionResult.Detected signal =
                    new SignalDetectionResult.Detected(rule.getKey(), RULE_CONFIDENCE, matched);

            if (rule.getKey().startsWith("S")) {
                stages.add(signal);
            } else {
                negatives.add(signal);
            }
        }
        return new SignalDetectionResult(stages, negatives);
    }

    /** 가장 먼저 매칭되는 문장을 근거로 쓴다. 화면 하이라이트에 그대로 들어간다. */
    private String firstMatch(List<String> lines, List<Pattern> patterns, boolean negative) {
        for (String line : lines) {
            if (negative && NEGATIVE_DENIAL.matcher(line).find()) {
                continue;
            }
            for (Pattern pattern : patterns) {
                if (pattern.matcher(line).find()) {
                    return line;
                }
            }
        }
        return null;
    }
}
