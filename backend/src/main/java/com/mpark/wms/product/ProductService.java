package com.mpark.wms.product;

import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository repo;
    private final CodeGenerator codeGenerator;

    @Transactional(readOnly = true)
    public List<Product> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Product get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Product create(ProductRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("상품명을 입력하세요.");
        Product p = new Product();
        p.setCode(codeGenerator.next("products", "P")); // 자동코드
        p.setSkuSeq(0);
        apply(p, r);
        return repo.save(p);
    }

    public Product update(String id, ProductRequest r) {
        Product p = repo.findById(id).orElseThrow(() -> ApiException.notFound("상품을 찾을 수 없습니다."));
        apply(p, r); // code/skuSeq 유지
        return repo.save(p);
    }

    public void remove(String id) {
        repo.deleteById(id);
    }

    private void apply(Product p, ProductRequest r) {
        if (!isBlank(r.name())) p.setName(r.name().trim());
        p.setMaker(nz(r.maker()));
        p.setBarcode(nz(r.barcode()));
        p.setNote(nz(r.note()));
        p.setMainImageUrl(nz(r.mainImageUrl()));
        p.setImages(r.images() != null ? r.images() : new ArrayList<>());
        p.setPrice(r.price() != null ? r.price() : BigDecimal.ZERO);
        p.setCategoryId(r.categoryId());
        p.setCategoryName(nz(r.categoryName()));
        p.setProductCodeId(r.productCodeId());
        p.setProductCodeName(nz(r.productCodeName()));
        p.setProductDetailId(r.productDetailId());
        p.setProductDetailName(nz(r.productDetailName()));
        p.setPathLabel(nz(r.pathLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
