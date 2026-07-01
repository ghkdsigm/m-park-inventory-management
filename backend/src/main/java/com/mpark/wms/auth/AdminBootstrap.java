package com.mpark.wms.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 관리자 자동 지정. 설정된 이메일(기본 admin@dongwha.com)로 가입한 계정이 있으면
 * 기동 시 role=admin 으로 승격(멱등). Supabase 의 "최초 관리자 수동 승격"을 대체.
 */
@Configuration
@RequiredArgsConstructor
public class AdminBootstrap {

    private final ProfileRepository repo;

    @Value("${app.bootstrap-admin-email:admin@dongwha.com}")
    private String adminEmail;

    @Bean
    public ApplicationRunner promoteFirstAdmin() {
        return args -> promote();
    }

    @Transactional
    void promote() {
        if (adminEmail == null || adminEmail.isBlank()) return;
        repo.findByEmail(adminEmail.trim()).ifPresent(p -> {
            if (!"admin".equals(p.getRole())) {
                p.setRole("admin");
                p.setCanStock(true);
                repo.save(p);
            }
        });
    }
}
