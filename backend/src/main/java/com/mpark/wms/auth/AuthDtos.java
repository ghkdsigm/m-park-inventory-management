package com.mpark.wms.auth;

import java.time.LocalDateTime;

/** 인증/사용자 DTO. password_hash 는 절대 응답에 포함하지 않는다(ProfileDto 사용). */
public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(String email, String password, String displayName) {}

    public record LoginRequest(String email, String password) {}

    public record ProfileDto(String id, String email, String displayName, String role,
                             boolean canStock, LocalDateTime createdAt) {}

    public record AuthResponse(String token, ProfileDto profile) {}

    public record RoleRequest(String role) {}

    public record StockPermRequest(boolean canStock) {}
}
