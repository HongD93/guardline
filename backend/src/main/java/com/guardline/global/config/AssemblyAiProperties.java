package com.guardline.global.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * AssemblyAI 실시간 스트리밍 접속 설정.
 *
 * <p>공식 에이전트 지침(assemblyai.com/docs/agent-instructions.md)을 따른다. 스트리밍은
 * PCM16 리틀엔디언 모노가 기본값이라 encoding을 명시하지 않아도 되며, 값이 어긋나면 연결이
 * 거부될 수 있어 비워두는 것을 기본으로 한다.
 *
 * @param apiKey       API 키. Authorization 헤더에 Bearer 접두사 없이 그대로 실린다.
 * @param endpoint     스트리밍 WebSocket 엔드포인트. 기본 도메인은 US 고정이 아니라 에지 라우팅이다.
 * @param sampleRate   업스트림에 알리는 샘플레이트. 프론트가 리샘플해 보내는 값과 반드시 같아야 한다.
 * @param encoding     오디오 인코딩. 비워두면 쿼리에 싣지 않고 기본값(PCM16 LE 모노)을 쓴다.
 * @param speechModel  사용할 음성 모델(단수 파라미터). 비워두면 AssemblyAI 기본값을 쓴다.
 * @param languageCode 인식 언어 편향. 한국어 통화를 다루므로 ko를 기본으로 둔다.
 */
@Slf4j
@ConfigurationProperties(prefix = "guardline.assemblyai")
public record AssemblyAiProperties(
        String apiKey,
        String endpoint,
        int sampleRate,
        String encoding,
        String speechModel,
        String languageCode
) {

    /**
     * 키가 없어도 서버는 뜬다. 오디오 파이프라인과 WebSocket 전송은 키 없이도 개발·검증할 수 있어야
     * 하므로, 실제 업스트림 연결을 여는 시점에만 막고 기동 시에는 경고만 남긴다.
     */
    @PostConstruct
    void warnIfKeyMissing() {
        if (!hasApiKey()) {
            log.warn("ASSEMBLYAI_API_KEY가 비어 있습니다. STT 연결을 시도하면 실패합니다.");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 설정값을 반영한 실제 접속 URI를 만든다. 비어 있는 선택 파라미터는 싣지 않는다. */
    public URI buildUri() {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("sample_rate", sampleRate);

        appendIfPresent(builder, "encoding", encoding);
        appendIfPresent(builder, "speech_model", speechModel);
        appendIfPresent(builder, "language_code", languageCode);

        return builder.build(true).toUri();
    }

    private static void appendIfPresent(UriComponentsBuilder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(name, value);
        }
    }
}
