package com.guardline.domain.risk.response;

/**
 * 신호 하나와 그 근거 문장. 화면에서 해당 문장을 하이라이트하는 데 그대로 쓴다.
 * 근거가 화면에 보이는 것과 안 보이는 것은 데모 설득력이 완전히 다르다.
 *
 * @param id         S1~S5 또는 N1~N4
 * @param confidence 단계 신호의 신뢰도. 감점 신호는 1.0으로 고정한다.
 * @param evidence   트랜스크립트 원문 그대로
 */
public record StageEvidenceResponseDTO(String id, double confidence, String evidence) {
}
