package com.mpark.wms.auth;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.auth.AuthDtos.*;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.security.CurrentUser;
import com.mpark.wms.common.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final ProfileRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public AuthResponse register(RegisterRequest r) {
        String email = norm(r.email());
        if (email.isBlank() || isBlank(r.password())) throw ApiException.badRequest("이메일과 비밀번호를 입력하세요.");
        if (r.password().length() < 6) throw ApiException.badRequest("비밀번호는 6자 이상이어야 합니다.");
        if (repo.findByEmail(email).isPresent()) throw ApiException.badRequest("이미 사용 중인 이메일입니다.");

        Profile p = new Profile();
        p.setEmail(email);
        p.setPasswordHash(encoder.encode(r.password()));
        p.setDisplayName(isBlank(r.displayName()) ? email : r.displayName().trim());
        p.setRole("user");      // 기본 일반
        p.setCanStock(false);   // 기본 조회만
        repo.save(p);
        return new AuthResponse(token(p), toDto(p));
    }

    public AuthResponse login(LoginRequest r) {
        String email = norm(r.email());
        Profile p = repo.findByEmail(email)
                .orElseThrow(() -> ApiException.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!encoder.matches(r.password() == null ? "" : r.password(), p.getPasswordHash()))
            throw ApiException.unauthorized("이메일 또는 비밀번호가 올바르지 않습니다.");
        auditService.log("인증", "로그인", p.getId(), p.getEmail(), p.getDisplayName());
        return new AuthResponse(token(p), toDto(p));
    }

    @Transactional(readOnly = true)
    public ProfileDto me() {
        String id = currentUser.id();
        if (id == null) throw ApiException.unauthorized("로그인이 필요합니다.");
        return repo.findById(id).map(AuthService::toDto)
                .orElseThrow(() -> ApiException.unauthorized("로그인이 필요합니다."));
    }

    /* ---------- 사용자 관리 (admin) ---------- */
    @Transactional(readOnly = true)
    public List<ProfileDto> listUsers() {
        return repo.findAllByOrderByCreatedAtAsc().stream().map(AuthService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ProfileDto getUser(String id) {
        return repo.findById(id).map(AuthService::toDto).orElse(null);
    }

    public void setRole(String id, String role) {
        if (!"admin".equals(role) && !"user".equals(role)) throw ApiException.badRequest("잘못된 권한입니다.");
        Profile p = repo.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        String before = p.getRole();
        p.setRole(role);
        auditService.log("권한관리", "권한변경", id, p.getEmail(), p.getDisplayName(), "role=" + before, "role=" + role);
    }

    public void setStockPerm(String id, boolean canStock) {
        Profile p = repo.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        boolean before = p.isCanStock();
        p.setCanStock(canStock);
        auditService.log("권한관리", "입출고권한변경", id, p.getEmail(), p.getDisplayName(), "canStock=" + before, "canStock=" + canStock);
    }

    private String token(Profile p) {
        return jwt.generate(p.getId(), p.getDisplayName(), p.getRole(), p.isCanStock());
    }

    static ProfileDto toDto(Profile p) {
        return new ProfileDto(p.getId(), p.getEmail(), p.getDisplayName(), p.getRole(), p.isCanStock(), p.getCreatedAt());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s", ""); }
}
