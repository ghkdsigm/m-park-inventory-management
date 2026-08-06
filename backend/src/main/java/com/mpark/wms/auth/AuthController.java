package com.mpark.wms.auth;

import com.mpark.wms.auth.AuthDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 인증 REST — db.js stores/auth.js 의 login/register/getSession 대체. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService service;

    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest r) { return service.login(r); }

    @GetMapping("/me")
    public ProfileDto me() { return service.me(); }
}
