package com.mpark.wms.common.security;

/** JWT 인증 주체 (SecurityContext principal). */
public record AuthUser(String id, String name, String role, boolean canStock) {
}
