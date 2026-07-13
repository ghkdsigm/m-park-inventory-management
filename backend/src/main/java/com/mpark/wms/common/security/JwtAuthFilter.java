package com.mpark.wms.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Authorization: Bearer <token> 를 읽어 SecurityContext 에 인증을 설정. */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            AuthUser user = jwtService.parse(header.substring(7));
            if (user != null) {
                String authority = "admin".equals(user.role()) ? "ROLE_ADMIN" : "ROLE_USER";
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority(authority)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * async 디스패치(스트리밍 응답, 예: /api/chat 의 StreamingResponseBody)에서도 이 필터를 실행한다.
     * OncePerRequestFilter 는 기본적으로 async 디스패치를 건너뛰는데, 그러면 스트리밍 완료 후
     * 재디스패치 시 SecurityContext 가 비어 인가 검사에서 AccessDeniedException 이 발생하고,
     * 이미 커밋된 응답의 청크 스트림이 중간에 끊긴다(ERR_INCOMPLETE_CHUNKED_ENCODING).
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
