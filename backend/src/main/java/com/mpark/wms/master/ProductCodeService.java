package com.mpark.wms.master;

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
        return repo.save(c);
    }

    public ProductCode update(String id, ProductCodeRequest r) {
        ProductCode c = repo.findById(id).orElseThrow(() -> ApiException.notFound("제품코드를 찾을 수 없습니다."));
        apply(c, r);
        return repo.save(c);
    }

    public void remove(String id) {
        repo.deleteById(id);
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
