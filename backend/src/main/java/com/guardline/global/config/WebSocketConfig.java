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

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(callRelayHandler, "/ws/call")
                .setAllowedOrigins(allowedOrigins);
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
