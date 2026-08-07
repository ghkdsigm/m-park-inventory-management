package com.mpark.wms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/** 사용자/권한 (Supabase auth.users + profiles 통합). 자체 인증이므로 password_hash 보유. */
@Getter
@Setter
@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    @Column(unique = true)
    private String username;           // 로그인 아이디

    @Column(unique = true)
    private String email;              // 선택(이메일)

    private String passwordHash;
    private String displayName;

    private String jobTitle = "";       // 직급 (소장/시설과장/미화실장 등) — column: job_title
    private String managedComplex = "";  // 관리단지 이름 (랜드/허브 등) — column: managed_complex

    private String role = "registrar"; // super | manager | registrar
    private boolean canStock = true;   // (역할로 대체된 하위호환 컬럼)

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void pre() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
