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

    public AuthResponse login(LoginRequest r) {
        String username = norm(r.username());
        Profile p = repo.findByUsername(username)
                .orElseThrow(() -> ApiException.unauthorized("아이디 또는 비밀번호가 올바르지 않습니다."));
        if (!encoder.matches(r.password() == null ? "" : r.password(), p.getPasswordHash()))
            throw ApiException.unauthorized("아이디 또는 비밀번호가 올바르지 않습니다.");
        auditService.log("인증", "로그인", p.getId(), p.getUsername(), p.getDisplayName());
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

    public ProfileDto createUser(UserUpsertRequest r) {
        String username = norm(r.username());
        if (username.isBlank() || isBlank(r.password())) throw ApiException.badRequest("아이디와 비밀번호를 입력하세요.");
        if (r.password().length() < 6) throw ApiException.badRequest("비밀번호는 6자 이상이어야 합니다.");
        if (repo.existsByUsername(username)) throw ApiException.badRequest("이미 사용 중인 아이디입니다.");
        String email = normEmail(r.email());
        if (email != null && repo.existsByEmail(email)) throw ApiException.badRequest("이미 사용 중인 이메일입니다.");
        String role = validRole(r.role());

        Profile p = new Profile();
        p.setUsername(username);
        p.setEmail(email);
        p.setPasswordHash(encoder.encode(r.password()));
        p.setDisplayName(isBlank(r.displayName()) ? username : r.displayName().trim());
        p.setJobTitle(nz(r.jobTitle()));
        p.setManagedComplex(nz(r.managedComplex()));
        p.setRole(role);
        p.setCanStock(true); // 세 역할 모두 입출고 가능(하위호환 컬럼)
        repo.save(p);
        auditService.log("사용자관리", "등록", p.getId(), p.getUsername(), p.getDisplayName(), null, "role=" + role);
        return toDto(p);
    }

    public ProfileDto updateUser(String id, UserUpsertRequest r) {
        Profile p = repo.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        String before = "role=" + p.getRole() + ", username=" + p.getUsername();

        if (!isBlank(r.username())) {
            String u = norm(r.username());
            if (!u.equals(p.getUsername()) && repo.existsByUsername(u)) throw ApiException.badRequest("이미 사용 중인 아이디입니다.");
            p.setUsername(u);
        }
        String email = normEmail(r.email());
        if (email != null && !email.equals(p.getEmail()) && repo.existsByEmail(email))
            throw ApiException.badRequest("이미 사용 중인 이메일입니다.");
        p.setEmail(email);
        if (!isBlank(r.displayName())) p.setDisplayName(r.displayName().trim());
        if (r.jobTitle() != null) p.setJobTitle(r.jobTitle().trim());
        if (r.managedComplex() != null) p.setManagedComplex(r.managedComplex().trim());
        if (!isBlank(r.role())) {
            String role = validRole(r.role());
            if (id.equals(currentUser.id()) && !"super".equals(role))
                throw ApiException.badRequest("본인 슈퍼관리자 권한은 해제할 수 없습니다.");
            p.setRole(role);
        }
        if (!isBlank(r.password())) {
            if (r.password().length() < 6) throw ApiException.badRequest("비밀번호는 6자 이상이어야 합니다.");
            p.setPasswordHash(encoder.encode(r.password()));
        }
        auditService.log("사용자관리", "수정", id, p.getUsername(), p.getDisplayName(), before, "role=" + p.getRole() + ", username=" + p.getUsername());
        return toDto(p);
    }

    public void deleteUser(String id) {
        if (id.equals(currentUser.id())) throw ApiException.badRequest("본인 계정은 삭제할 수 없습니다.");
        Profile p = repo.findById(id).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
        auditService.log("사용자관리", "삭제", id, p.getUsername(), p.getDisplayName());
        repo.delete(p);
    }

    private static String validRole(String role) {
        if (!"super".equals(role) && !"manager".equals(role) && !"registrar".equals(role))
            throw ApiException.badRequest("잘못된 권한입니다. (super/manager/registrar)");
        return role;
    }

    private String token(Profile p) {
        return jwt.generate(p.getId(), p.getDisplayName(), p.getRole(), p.isCanStock());
    }

    static ProfileDto toDto(Profile p) {
        return new ProfileDto(p.getId(), p.getUsername(), p.getEmail(), p.getDisplayName(),
                p.getJobTitle(), p.getManagedComplex(), p.getRole(), p.isCanStock(), p.getCreatedAt());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s", ""); }
    /** 이메일 정규화: 공백 제거 후 비어있으면 null(선택 항목). */
    private static String normEmail(String s) { String e = norm(s); return e.isBlank() ? null : e; }
}
