package com.guardline.domain.risk.response;

import java.util.List;

/**
 * 브라우저로 내려보내는 판정 결과. 점수만 주면 화면에서 이유를 설명할 수 없으므로
 * 계산 과정(체인 길이, 배수, 감점)과 근거 문장을 함께 싣는다.
 *
 * @param score          0~100
 * @param level          안전 / 주의 / 경고 / 위험
 * @param chain          순서대로 밟힌 최장 연속 단계 수
 * @param multiplier     체인 배수
 * @param penalty        적용된 감점 합계
 * @param isolationFloor 격리 유도 단독 트리거가 걸렸는지
 * @param negatives      적용된 감점 신호 id
 * @param evidences      감지된 신호와 근거 문장
 */
public record RiskAssessmentResponseDTO(
        double score,
        String level,
        int chain,
        double multiplier,
        double penalty,
        boolean isolationFloor,
        List<String> negatives,
        List<StageEvidenceResponseDTO> evidences
) {
}
