package com.guardline.domain.risk.service;

import com.guardline.domain.risk.RiskLevel;
import com.guardline.domain.risk.response.RiskAssessmentResponseDTO;
import com.guardline.domain.risk.response.StageEvidenceResponseDTO;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 위험도 점수를 계산한다.
 *
 * <p>LLM은 신호 감지만 하고 점수는 이 결정적 함수가 낸다. LLM에 점수를 맡기면 같은 입력에 다른
 * 값이 나와 심사에서 근거를 설명할 수 없다. 참조 구현은 tools/score.mjs에 있고 두 구현의
 * 결과는 시나리오 7종에서 일치해야 한다.
 */
@Component
public class RiskScorer {

    /** 단계별 가중치. 격리 유도(S3)가 자산 이동(S5) 다음으로 높은 이유는 룰셋 설계 문서 참고. */
    private static final Map<String, Integer> STAGE_WEIGHT =
            Map.of("S1", 15, "S2", 20, "S3", 30, "S4", 25, "S5", 40);

    /** 감점 신호. N1(제3자 상의 권유)은 S3의 정반대 신호라 가장 크다. */
    private static final Map<String, Integer> NEGATIVE_WEIGHT =
            Map.of("N1", 40, "N2", 30, "N3", 25, "N4", 15);

    /** 순서대로 밟힌 최장 연속 체인 길이별 배수. 인덱스가 곧 체인 길이다. */
    private static final double[] CHAIN_MULTIPLIER = {1.0, 1.0, 1.15, 1.35, 1.6, 1.9};

    private static final List<String> STAGE_ORDER = List.of("S1", "S2", "S3", "S4", "S5");

    /** 격리 유도가 이 신뢰도 이상이면 다른 신호와 무관하게 위험 등급을 보장한다. */
    private static final double ISOLATION_THRESHOLD = 0.8;
    private static final double ISOLATION_FLOOR = 80.0;

    /**
     * @param stages    단계별 신뢰도. 통화 종료까지 래치되어 누적된 상태가 들어온다.
     * @param negatives 감지된 감점 신호 id
     * @param inbound   수신 통화 여부. false면 사용자가 건 전화라 N3이 적용된다.
     */
    public RiskAssessmentResponseDTO score(
            Map<String, Double> stages,
            Set<String> negatives,
            boolean inbound,
            List<StageEvidenceResponseDTO> evidences
    ) {
        double raw = STAGE_ORDER.stream()
                .mapToDouble(id -> STAGE_WEIGHT.get(id) * stages.getOrDefault(id, 0.0))
                .sum();

        int chain = longestChain(stages);
        double chained = raw * CHAIN_MULTIPLIER[chain];

        Set<String> applied = new java.util.HashSet<>(negatives);
        if (!inbound) {
            applied.add("N3");
        }
        double penalty = applied.stream()
                .mapToDouble(id -> NEGATIVE_WEIGHT.getOrDefault(id, 0))
                .sum();

        double value = Math.clamp(chained - penalty, 0.0, 100.0);

        // 정상 기관은 어떤 경우에도 정보 차단을 요구하지 않는다. 격리가 확인된 시점에 개입해야
        // 앱 설치(S4)와 이체(S5) 이전에 끊을 수 있다.
        boolean isolation = stages.getOrDefault("S3", 0.0) >= ISOLATION_THRESHOLD;
        if (isolation) {
            value = Math.max(value, ISOLATION_FLOOR);
        }

        double rounded = Math.round(value * 10.0) / 10.0;

        return new RiskAssessmentResponseDTO(
                rounded,
                RiskLevel.of(rounded).getLabel(),
                chain,
                CHAIN_MULTIPLIER[chain],
                penalty,
                isolation,
                applied.stream().sorted().toList(),
                evidences.stream()
                        .sorted(Comparator.comparing(StageEvidenceResponseDTO::id))
                        .toList()
        );
    }

    /** 순서대로 밟힌 단계의 최장 연속 길이. S1→S2→S3은 3, S1과 S5만 있으면 1이다. */
    private int longestChain(Map<String, Double> stages) {
        int best = 0;
        int run = 0;

        for (String id : STAGE_ORDER) {
            if (stages.getOrDefault(id, 0.0) > 0) {
                run += 1;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
        }
        return best;
    }
}
