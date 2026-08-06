package com.mpark.wms.config;

import com.mpark.wms.common.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 보안 — JWT 인증 + URL 권한.
 * 정책(Supabase RLS 대응): 조회=로그인 사용자 / 쓰기(관리)=admin / 입·출고=서비스에서 can_stock 검증.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(401, "Unauthorized")))
                .authorizeHttpRequests(auth -> auth
                        // 공개
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/files/quotes/**").authenticated() // 견적 PDF(단가 등 민감) — 인증 필요
                        .requestMatchers("/files/**").permitAll()           // 이미지 등 나머지는 공개
                        // 슈퍼관리자 전용 (사용자관리/감사로그/AI사용량)
                        .requestMatchers("/api/users/**", "/api/audit-logs", "/api/audit/**", "/api/ai-usage/**").hasRole("SUPER")
                        // 등록인 이상 허용: 입고/출고 + 챗봇 + SKU 검색용 조회 POST
                        .requestMatchers(HttpMethod.POST,
                                "/api/chat", "/api/chat/find-similar", "/api/chat/tts",
                                "/api/skus/page", "/api/skus/page-by-sku", "/api/skus/manage-page", "/api/skus/group-by-complex", "/api/skus/by-ids",
                                "/api/stock/inbound", "/api/stock/outbound").hasAnyRole("SUPER", "MANAGER", "REGISTRAR")
                        // 매니저 이상: 그 외 재고 조작(이동/조정/실사/검증/취소/연한교체)
                        .requestMatchers(HttpMethod.POST,
                                "/api/stock/transfer", "/api/stock/adjust",
                                "/api/stock/*/verify", "/api/stock/*/audit-resolve", "/api/stock/*/replace-lifecycle",
                                "/api/movements/*/void").hasAnyRole("SUPER", "MANAGER")
                        // 그 외 쓰기(생성/수정/삭제) = 매니저 이상
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("SUPER", "MANAGER")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("SUPER", "MANAGER")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("SUPER", "MANAGER")
                        // 나머지(GET 조회) = 로그인 사용자
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
