package com.guardline.domain.call.response;

import com.guardline.domain.risk.response.RiskAssessmentResponseDTO;

/**
 * 판정 결과를 브라우저로 내려보낼 때의 봉투. 전사 이벤트와 같은 소켓을 쓰므로
 * 프론트가 type으로 구분할 수 있어야 한다.
 */
public record RiskEventResponseDTO(String type, RiskAssessmentResponseDTO risk) {

    public static RiskEventResponseDTO of(RiskAssessmentResponseDTO risk) {
        return new RiskEventResponseDTO("risk", risk);
    }
}
