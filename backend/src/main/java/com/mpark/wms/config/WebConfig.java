package com.mpark.wms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 업로드된 이미지를 /files/** 로 공개 서빙 (Supabase Storage 공개 URL 대체). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.storage.dir}")
    private String dir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path base = Paths.get(dir).toAbsolutePath().normalize();
        String location = base.toUri().toString(); // file:/.../uploads/
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}
