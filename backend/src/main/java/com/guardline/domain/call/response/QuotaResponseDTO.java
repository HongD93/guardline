package com.guardline.domain.call.response;

/**
 * 남은 체험 횟수. 브라우저 연결 직후 내려보내 화면에 표시한다.
 * 제한이 있다는 사실을 감추지 않는 편이 심사에서도 설명하기 쉽다.
 *
 * @param type              이벤트 타입 구분용. 항상 "quota"
 * @param remainingToday    오늘 남은 세션 수
 * @param dailyLimit        일일 한도
 * @param maxDurationSecond 세션 하나의 최대 길이(초)
 */
public record QuotaResponseDTO(
        String type,
        int remainingToday,
        int dailyLimit,
        long maxDurationSecond
) {

    public QuotaResponseDTO(int remainingToday, int dailyLimit, long maxDurationSecond) {
        this("quota", remainingToday, dailyLimit, maxDurationSecond);
    }
}
