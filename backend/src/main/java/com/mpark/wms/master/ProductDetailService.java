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
public class ProductDetailService {

    private final ProductDetailRepository repo;
    private final CodeGenerator codeGenerator;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ProductDetail> list() {
        return repo.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public ProductDetail get(String id) {
        return repo.findById(id).orElse(null);
    }

    public ProductDetail create(ProductDetailRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("이름을 입력하세요.");
        ProductDetail c = new ProductDetail();
        c.setCode(codeGenerator.next("product_details", "PCD"));
        apply(c, r);
        ProductDetail saved = repo.save(c);
        auditService.log("기준정보", "상세코드 생성", saved.getId(), saved.getName(), saved.getName(), null, "name=" + saved.getName());
        return saved;
    }

    public ProductDetail update(String id, ProductDetailRequest r) {
        ProductDetail c = repo.findById(id).orElseThrow(() -> ApiException.notFound("제품상세코드를 찾을 수 없습니다."));
        String before = c.getName();
        apply(c, r);
        ProductDetail saved = repo.save(c);
        auditService.log("기준정보", "상세코드 수정", id, saved.getName(), saved.getName(), "name=" + before, "name=" + saved.getName());
        return saved;
    }

    public void remove(String id) {
        ProductDetail c = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (c != null) auditService.log("기준정보", "상세코드 삭제", id, c.getName(), c.getName(), "name=" + c.getName(), null);
    }

    private void apply(ProductDetail c, ProductDetailRequest r) {
        if (!isBlank(r.name())) c.setName(r.name().trim());
        c.setDescription(nz(r.description()));
        c.setProductCodeId(r.productCodeId());
        c.setProductCodeName(nz(r.productCodeName()));
        c.setCategoryId(r.categoryId());
        c.setCategoryName(nz(r.categoryName()));
        c.setPathLabel(nz(r.pathLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
