package com.mpark.wms.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** JWT 발급/검증 (Supabase Auth 대체). */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generate(String id, String name, String role, boolean canStock) {
        Date now = new Date();
        return Jwts.builder()
                .subject(id)
                .claim("name", name)
                .claim("role", role)
                .claim("canStock", canStock)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    /** 유효하면 AuthUser, 아니면 null */
    public AuthUser parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new AuthUser(c.getSubject(), c.get("name", String.class),
                    c.get("role", String.class), Boolean.TRUE.equals(c.get("canStock", Boolean.class)));
        } catch (Exception e) {
            return null;
        }
    }
}
