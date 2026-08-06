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

    /** 슈퍼관리자: 사용자관리/감사로그/AI사용량 등 최상위 권한 */
    public boolean isSuper() {
        AuthUser u = get();
        return u != null && "super".equals(u.role());
    }

    /** 백오피스 관리(기준정보/상품/위치 CRUD, 재고조정·실사·이동): 슈퍼관리자 또는 매니저 */
    public boolean canManage() {
        AuthUser u = get();
        return u != null && ("super".equals(u.role()) || "manager".equals(u.role()));
    }

    /** 입/출고 가능: 슈퍼관리자·매니저·등록인 모두 */
    public boolean canStock() {
        AuthUser u = get();
        return u != null && ("super".equals(u.role()) || "manager".equals(u.role()) || "registrar".equals(u.role()));
    }
}
