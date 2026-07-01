package com.mpark.wms.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 현재 로그인 사용자 (Supabase auth.uid()/display_name + role/can_stock 역할).
 * JWT 필터가 세팅한 SecurityContext 의 AuthUser 를 읽는다.
 */
@Component
public class CurrentUser {

    public AuthUser get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AuthUser u) return u;
        return null;
    }

    public String id() {
        AuthUser u = get();
        return u == null ? null : u.id();
    }

    public String name() {
        AuthUser u = get();
        return u == null ? null : u.name();
    }

    public boolean isAdmin() {
        AuthUser u = get();
        return u != null && "admin".equals(u.role());
    }

    /** 입/출고 가능: 관리자 또는 can_stock 권한 보유 */
    public boolean canStock() {
        AuthUser u = get();
        return u != null && ("admin".equals(u.role()) || u.canStock());
    }
}
