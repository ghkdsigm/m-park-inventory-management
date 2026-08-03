package com.mpark.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 엠파크 WMS 백엔드 (Spring Boot + MySQL).
 * Supabase(PostgreSQL + RPC + Auth + Storage) 를 대체한다.
 */
@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class WmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(WmsApplication.class, args);
    }
}
