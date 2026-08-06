package com.mpark.wms.auth;

import com.mpark.wms.auth.AuthDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 사용자 관리 REST (슈퍼관리자 전용 — SecurityConfig). 목록/조회/생성/수정/삭제. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService service;

    @GetMapping
    public List<ProfileDto> list() { return service.listUsers(); }

    @GetMapping("/{id}")
    public ProfileDto get(@PathVariable String id) { return service.getUser(id); }

    @PostMapping
    public ProfileDto create(@RequestBody UserUpsertRequest r) { return service.createUser(r); }

    @PutMapping("/{id}")
    public ProfileDto update(@PathVariable String id, @RequestBody UserUpsertRequest r) { return service.updateUser(id, r); }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) { service.deleteUser(id); }
}
