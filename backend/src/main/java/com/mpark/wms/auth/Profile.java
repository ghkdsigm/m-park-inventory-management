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
    private String email;

    private String passwordHash;
    private String displayName;

    private String role = "user";      // admin | user
    private boolean canStock = false;  // 입/출고 권한

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void pre() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
