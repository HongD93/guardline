package com.guardline.domain.call.client;

import tools.jackson.databind.ObjectMapper;
import com.guardline.global.config.AssemblyAiProperties;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

/** AssemblyAI 실시간 스트리밍 연결을 연다. API 키는 이 계층 밖으로 나가지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssemblyAiStreamClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final AssemblyAiProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 업스트림 연결을 열고 핸들을 돌려준다. 연결이 완료될 때까지 대기하므로 브라우저 세션의
     * 메시지 처리 스레드에서 호출된다는 점을 감안해 타임아웃을 짧게 잡았다.
     */
    public AssemblyAiConnection open(UpstreamListener listener) throws Exception {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException(
                    "ASSEMBLYAI_API_KEY가 설정되지 않았습니다. 환경변수 또는 backend/.env에 키를 넣고 서버를 다시 시작하세요.");
        }
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, properties.apiKey()); // Bearer 접두사를 붙이지 않는다

        AssemblyAiConnection connection = new AssemblyAiConnection(objectMapper, listener);

        log.info("업스트림 STT 연결 시도: {}", properties.endpoint());
        new StandardWebSocketClient()
                .execute(connection, headers, properties.buildUri())
                .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        return connection;
    }
}
