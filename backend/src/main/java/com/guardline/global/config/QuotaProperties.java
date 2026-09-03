package com.guardline.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공개 배포에서 API 크레딧을 지키는 상한.
 *
 * <p>배포 URL은 심사위원 누구나 접근한다. 한 번 재생할 때마다 STT와 LLM 크레딧이 나가므로
 * 상한이 없으면 반복 클릭이나 봇 트래픽에 크레딧이 마르고, 정작 심사 시점에 동작하지 않는다.
 * 접근 비밀번호는 심사위원을 막는 부작용이 있어 상한 방식을 택했다.
 *
 * @param dailySessions  하루 총 통화 세션 수. 자정(서버 시간) 기준으로 초기화된다.
 * @param concurrent     동시 세션 수
 * @param maxDurationMs  세션 하나의 최대 길이. 넘으면 서버가 끊는다.
 */
@ConfigurationProperties(prefix = "guardline.quota")
public record QuotaProperties(
        int dailySessions,
        int concurrent,
        long maxDurationMs
) {
}
