package com.guardline.global.config;

import com.guardline.domain.call.handler.CallRelayHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    /** 오디오 청크는 4KB 안팎이지만 프론트가 버퍼를 키워도 끊기지 않도록 여유를 둔다. */
    private static final int MAX_BINARY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_TEXT_BUFFER_BYTES = 32 * 1024;
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final CallRelayHandler callRelayHandler;

    @Value("${guardline.cors.allowed-origins}")
    private String[] allowedOrigins;

    /**
     * 허용 오리진이 비어 있으면 지정하지 않는다. Spring 기본값이 동일 오리진만 허용하는데,
     * 배포에서는 프론트를 같은 서버가 서빙하므로 그게 정확히 맞고 가장 안전하다.
     * 개발 중에만 Vite 개발 서버 오리진을 열어준다.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(callRelayHandler, "/ws/call");

        String[] origins = allowedOrigins == null ? new String[0] : allowedOrigins;
        if (origins.length > 0 && !origins[0].isBlank()) {
            registration.setAllowedOrigins(origins);
        }
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_BUFFER_BYTES);
        container.setMaxTextMessageBufferSize(MAX_TEXT_BUFFER_BYTES);
        container.setMaxSessionIdleTimeout(IDLE_TIMEOUT_MS);
        return container;
    }
}
