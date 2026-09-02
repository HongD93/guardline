package com.guardline.domain.risk.client;

import java.util.List;

/**
 * LLM이 감지한 신호 목록. 점수는 들어 있지 않다 - 채점은 결정적 함수의 몫이다.
 *
 * @param stages    감지된 단계 신호
 * @param negatives 감지된 감점 신호
 */
public record SignalDetectionResult(List<Detected> stages, List<Detected> negatives) {

    /**
     * @param id         신호 식별자
     * @param confidence 0.0~1.0. 감점 신호는 1.0으로 채워진다.
     * @param evidence   트랜스크립트 원문에서 그대로 뽑은 근거 문장
     */
    public record Detected(String id, double confidence, String evidence) {
    }

    public static SignalDetectionResult empty() {
        return new SignalDetectionResult(List.of(), List.of());
    }
}
