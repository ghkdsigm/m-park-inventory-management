package com.mpark.wms.config;

import com.mpark.wms.common.security.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 업로드 파일 서빙(/files/**) + AI 엔드포인트 레이트리밋 등록. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.storage.dir}")
    private String dir;

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path base = Paths.get(dir).toAbsolutePath().normalize();
        String location = base.toUri().toString(); // file:/.../uploads/
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat", "/api/chat/**", "/api/quotes/upload");
    }
}
