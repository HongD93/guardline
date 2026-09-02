package com.guardline.domain.risk.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.guardline.domain.risk.response.RiskAssessmentResponseDTO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 참조 구현(tools/score.mjs)과 같은 값을 내는지 고정한다.
 * 두 구현이 갈라지면 데모에서 보여준 점수와 발표 자료의 숫자가 어긋난다.
 */
class RiskScorerTest {

    private final RiskScorer scorer = new RiskScorer();

    private RiskAssessmentResponseDTO score(Map<String, Double> stages, Set<String> negatives, boolean inbound) {
        return scorer.score(stages, negatives, inbound, List.of());
    }

    @Test
    @DisplayName("정상 은행 카드 재발급 - S1이 점등돼도 공식 채널 재확인 유도로 상쇄된다")
    void 정상_은행_카드재발급은_안전이다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.8), Set.of("N2"), true);

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("가족 간 송금 - S5 단독은 체인 배수가 1.0이라 터지지 않는다")
    void 자산이동_단독신호는_안전이다() {
        RiskAssessmentResponseDTO result = score(Map.of("S5", 0.7), Set.of(), true);

        assertThat(result.score()).isEqualTo(28.0);
        assertThat(result.chain()).isEqualTo(1);
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("카드사 이상거래 안내 - 사기와 같은 S1+S2를 밟지만 감점 신호가 판별한다")
    void 최난도_반증케이스는_안전이다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.9, "S2", 0.7), Set.of("N2", "N1"), true);

        assertThat(result.score()).isZero();
        assertThat(result.chain()).isEqualTo(2);
        assertThat(result.penalty()).isEqualTo(70.0);
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("보험 텔레마케팅 - 제3자 상의 권유가 S1을 상쇄한다")
    void 보험_텔레마케팅은_안전이다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.6), Set.of("N1"), true);

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("중고거래 - 사용자가 건 전화면 N3이 자동 적용된다")
    void 사용자발신_통화는_감점된다() {
        RiskAssessmentResponseDTO result = score(Map.of("S5", 0.8), Set.of(), false);

        assertThat(result.score()).isEqualTo(7.0);
        assertThat(result.negatives()).containsExactly("N3");
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("관공서 안내 - 금전 언급 없이 종결되면 오르지 않는다")
    void 관공서_안내는_안전이다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.7), Set.of("N4"), true);

        assertThat(result.score()).isZero();
        assertThat(result.level()).isEqualTo("안전");
    }

    @Test
    @DisplayName("검찰 사칭 - 5단계를 순서대로 밟으면 배수 1.9가 걸려 상한에 도달한다")
    void 검찰사칭_전체단계는_위험이다() {
        RiskAssessmentResponseDTO result = score(
                Map.of("S1", 0.95, "S2", 0.9, "S3", 0.9, "S4", 0.85, "S5", 0.8), Set.of(), true);

        assertThat(result.score()).isEqualTo(100.0);
        assertThat(result.chain()).isEqualTo(5);
        assertThat(result.multiplier()).isEqualTo(1.9);
        assertThat(result.level()).isEqualTo("위험");
    }

    @Test
    @DisplayName("격리 유도가 확인되면 앱 설치 전에 위험 등급에 도달한다")
    void 격리유도_단독트리거가_위험등급을_보장한다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.95, "S2", 0.9, "S3", 0.9), Set.of(), true);

        assertThat(result.score()).isEqualTo(80.0);
        assertThat(result.isolationFloor()).isTrue();
        assertThat(result.level()).isEqualTo("위험");
    }

    @Test
    @DisplayName("건너뛴 단계가 있으면 체인이 끊겨 배수가 붙지 않는다")
    void 순서를_밟지_않으면_체인이_끊긴다() {
        RiskAssessmentResponseDTO result = score(Map.of("S1", 1.0, "S5", 1.0), Set.of(), true);

        assertThat(result.chain()).isEqualTo(1);
        assertThat(result.multiplier()).isEqualTo(1.0);
        assertThat(result.score()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("표시 점수와 판정 등급이 어긋나지 않는다")
    void 반올림된_점수로_등급을_매긴다() {
        // S3 신뢰도가 0.8 미만이라 격리 단독 트리거가 걸리지 않는다. 참조 구현(score.mjs)과
        // 같은 75.5가 나와야 한다.
        RiskAssessmentResponseDTO result = score(Map.of("S1", 0.95, "S2", 0.9, "S3", 0.79), Set.of(), true);

        assertThat(result.isolationFloor()).isFalse();
        assertThat(result.score()).isEqualTo(75.5);
        assertThat(result.level()).isEqualTo("경고");
    }
}
