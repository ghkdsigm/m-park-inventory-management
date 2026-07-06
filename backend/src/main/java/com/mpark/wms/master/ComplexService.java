package com.mpark.wms.master;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ComplexService {

    private final ComplexRepository repo;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Complex> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public Complex get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Complex create(ComplexRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        if (isBlank(r.code())) throw ApiException.badRequest("코드를 입력하세요.");
        Complex c = new Complex();
        c.setCode(r.code().trim());
        c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        Complex saved = repo.save(c);
        auditService.log("위치관리", "단지 생성", saved.getId(), saved.getCode(), saved.getName(), null, "name=" + saved.getName());
        return saved;
    }

    public Complex update(String id, ComplexRequest r) {
        Complex c = repo.findById(id).orElseThrow(() -> ApiException.notFound("단지를 찾을 수 없습니다."));
        String before = c.getName();
        if (!isBlank(r.code())) c.setCode(r.code().trim());
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        Complex saved = repo.save(c);
        auditService.log("위치관리", "단지 수정", id, saved.getCode(), saved.getName(), "name=" + before, "name=" + saved.getName());
        return saved;
    }

    public void remove(String id) {
        Complex c = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (c != null) auditService.log("위치관리", "단지 삭제", id, c.getCode(), c.getName(), "name=" + c.getName(), null);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
