package com.guardline.global.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 프론트 빌드 산출물을 같은 서버에서 서빙한다.
 *
 * <p>프론트와 백엔드를 따로 배포하면 오리진이 갈라져 CORS 허용 목록, {@code wss://} 스킴,
 * 인증서를 배포할 때마다 맞춰야 한다. 한 오리진으로 묶으면 개발 중 Vite 프록시가 하던 역할을
 * 그대로 서버가 맡아 설정이 통째로 사라진다.
 *
 * <p>빌드 파이프라인이 {@code frontend/dist}를 {@code resources/static}으로 복사한다.
 * 시나리오 음성도 그 안에 함께 들어간다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {

                    /**
                     * 실제 파일이 없으면 index.html로 돌려보낸다. 지금은 라우터가 없어 경로가
                     * 하나뿐이지만, 새로고침이나 오타 경로에서 404 대신 화면이 뜨게 한다.
                     * WebSocket 경로(/ws/**)는 이 핸들러를 타지 않는다.
                     */
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        return new org.springframework.core.io.ClassPathResource("/static/index.html");
                    }
                });
    }
}
