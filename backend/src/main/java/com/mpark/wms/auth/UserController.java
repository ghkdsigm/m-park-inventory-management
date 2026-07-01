package com.mpark.wms.auth;

import com.mpark.wms.auth.AuthDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 사용자 관리 REST (admin) — db.js 의 users.list/get/setRole/setStockPerm. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService service;

    @GetMapping
    public List<ProfileDto> list() { return service.listUsers(); }

    @GetMapping("/{id}")
    public ProfileDto get(@PathVariable String id) { return service.getUser(id); }

    @PutMapping("/{id}/role")
    public void setRole(@PathVariable String id, @RequestBody RoleRequest r) { service.setRole(id, r.role()); }

    @PutMapping("/{id}/stock-perm")
    public void setStockPerm(@PathVariable String id, @RequestBody StockPermRequest r) { service.setStockPerm(id, r.canStock()); }
}
