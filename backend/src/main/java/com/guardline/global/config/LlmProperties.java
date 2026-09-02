package com.guardline.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AssemblyAI LLM Gateway 설정. STT와 같은 API 키를 쓰므로 별도 시크릿이 없다.
 *
 * @param endpoint     OpenAI 호환 chat completions 엔드포인트
 * @param model        신호 감지에 쓸 모델
 * @param maxTokens    응답 상한. 신호 목록만 받으므로 크게 잡을 이유가 없다.
 * @param timeoutMs    호출 타임아웃. 통화 중 실시간 판정이라 길게 기다릴 수 없다.
 * @param intervalMs   판정을 돌리는 고정 주기. 문장마다 호출하면 비용과 레이트 리밋이 감당이 안 된다.
 * @param windowLines  판정에 넘길 최근 확정 문장 수
 */
@ConfigurationProperties(prefix = "guardline.llm")
public record LlmProperties(
        String endpoint,
        String model,
        int maxTokens,
        int timeoutMs,
        int intervalMs,
        int windowLines
) {
}
