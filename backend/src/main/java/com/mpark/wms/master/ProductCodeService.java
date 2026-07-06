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
public class ProductCodeService {

    private final ProductCodeRepository repo;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ProductCode> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public ProductCode get(String id) {
        return repo.findById(id).orElse(null);
    }

    public ProductCode create(ProductCodeRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        ProductCode c = new ProductCode();
        c.setCode(codeGenerator.next("product_codes", "PC"));
        apply(c, r);
        ProductCode saved = repo.save(c);
        auditService.log("기준정보", "제품코드 생성", saved.getId(), saved.getName(), saved.getName(), null, "name=" + saved.getName());
        return saved;
    }

    public ProductCode update(String id, ProductCodeRequest r) {
        ProductCode c = repo.findById(id).orElseThrow(() -> ApiException.notFound("제품코드를 찾을 수 없습니다."));
        String before = c.getName();
        apply(c, r);
        ProductCode saved = repo.save(c);
        auditService.log("기준정보", "제품코드 수정", id, saved.getName(), saved.getName(), "name=" + before, "name=" + saved.getName());
        return saved;
    }

    public void remove(String id) {
        ProductCode c = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (c != null) auditService.log("기준정보", "제품코드 삭제", id, c.getName(), c.getName(), "name=" + c.getName(), null);
    }

    private void apply(ProductCode c, ProductCodeRequest r) {
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        c.setCategoryId(r.categoryId());
        c.setCategoryName(nz(r.categoryName()));
        c.setPathLabel(nz(r.pathLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
