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
 * <p>대상 범위를 좁게 잡았다.
 * <ul>
 *   <li>감점 신호(N1·N2·N4)는 전부 - 오탐이 나도 안전 쪽으로 밀 뿐이라 위험을 놓치지 않는다.</li>
 *   <li>단계 신호 중에는 S3(격리 유도)만 - 정상 통화에 거의 나오지 않는 고정밀 패턴이고
 *       위험 등급 floor를 트리거하는 제품의 핵심이다.</li>
 * </ul>
 * S1·S2·S4·S5는 정상 통화와 겹치는 표현이 많아 규칙으로 잡으면 오탐이 위험 쪽으로 튄다.
 * 그쪽은 LLM 판단에 맡긴다.
 */
@Component
public class KeywordSignalDetector {

    /** 규칙으로 확정한 신호의 신뢰도. S3 단독 트리거(0.8) 조건을 넘겨야 한다. */
    private static final double RULE_CONFIDENCE = 0.95;

    private static final Map<String, List<Pattern>> RULES = new LinkedHashMap<>();

    static {
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
                compile("대표\\s*번호"),
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
            String matched = firstMatch(lines, rule.getValue());
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
    private String firstMatch(List<String> lines, List<Pattern> patterns) {
        for (String line : lines) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(line).find()) {
                    return line;
                }
            }
        }
        return null;
    }
}
