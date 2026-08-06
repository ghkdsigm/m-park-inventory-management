package com.mpark.wms.auth;

import java.time.LocalDateTime;

/** 인증/사용자 DTO. password_hash 는 절대 응답에 포함하지 않는다(ProfileDto 사용). */
public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(String username, String password) {}

    public record ProfileDto(String id, String username, String email, String displayName, String role,
                             boolean canStock, LocalDateTime createdAt) {}

    public record AuthResponse(String token, ProfileDto profile) {}

    /** 사용자 생성/수정 요청 (관리자). 수정 시 password 가 비어있으면 비번 유지. */
    public record UserUpsertRequest(String username, String password, String displayName,
                                    String email, String role) {}
}
