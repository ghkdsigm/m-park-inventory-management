package com.mpark.wms.master;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CategoryService {

    private final CategoryRepository repo;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Category> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public Category get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Category create(CategoryRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        Category c = new Category();
        c.setCode(codeGenerator.next("categories", "CTG"));   // 자동코드
        apply(c, r);
        Category saved = repo.save(c);
        auditService.log("기준정보", "카테고리 생성", saved.getId(), saved.getName(), saved.getName(), null, "name=" + saved.getName());
        return saved;
    }

    public Category update(String id, CategoryRequest r) {
        Category c = repo.findById(id).orElseThrow(() -> ApiException.notFound("카테고리를 찾을 수 없습니다."));
        String before = c.getName();
        apply(c, r);   // code 는 유지
        Category saved = repo.save(c);
        auditService.log("기준정보", "카테고리 수정", id, saved.getName(), saved.getName(), "name=" + before, "name=" + saved.getName());
        return saved;
    }

    public void remove(String id) {
        Category c = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (c != null) auditService.log("기준정보", "카테고리 삭제", id, c.getName(), c.getName(), "name=" + c.getName(), null);
    }

    private void apply(Category c, CategoryRequest r) {
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        c.setPathLabel(nz(r.pathLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
