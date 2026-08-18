package com.mpark.wms.product;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import com.mpark.wms.location.StorageLocation;
import com.mpark.wms.master.Category;
import com.mpark.wms.master.Complex;
import com.mpark.wms.product.ProductDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
    private final AuditService auditService;
    private final ProductQueryRepository queryRepo;
    @PersistenceContext private EntityManager em;

    @Transactional(readOnly = true)
    public List<Product> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public ProductPageResult managePage(ProductFilter f) {
        return queryRepo.managePage(f);
    }

    @Transactional(readOnly = true)
    public Product get(String id) {
        return repo.findById(id).orElse(null);
    }

    public Product create(ProductRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("상품명을 입력하세요.");
        if (isBlank(r.complexId())) throw ApiException.badRequest("단지를 선택하세요.");
        if (isBlank(r.categoryId())) throw ApiException.badRequest("카테고리를 선택하세요.");
        Complex cx = em.find(Complex.class, r.complexId());
        if (cx == null) throw ApiException.badRequest("단지를 찾을 수 없습니다.");
        Category cat = em.find(Category.class, r.categoryId());
        if (cat == null) throw ApiException.badRequest("카테고리를 찾을 수 없습니다.");

        Product p = new Product();
        // 상품코드 = {단지코드}-{카테고리}-{순번4}. 순번은 (단지+카테고리)별로 1부터.
        long seq = codeGenerator.nextValue("products:" + cx.getId() + ":" + cat.getId());
        String catToken = nz(cat.getName()).replaceAll("\\s", "");
        p.setCode(cx.getCode() + "-" + catToken + "-" + String.format("%04d", seq));
        p.setSkuSeq(0);
        apply(p, r);
        // 단지 확정
        p.setComplexId(cx.getId());
        p.setComplexName(nz(cx.getName()));
        // 보관위치(선택) — 지정된 경우에만 위치 소속을 채운다. SKU 가 이걸 상속(있으면 재고행 자동 생성, 없으면 입고 때 지정).
        if (!isBlank(r.storageLocationId())) {
            StorageLocation loc = em.find(StorageLocation.class, r.storageLocationId());
            if (loc == null) throw ApiException.badRequest("보관위치를 찾을 수 없습니다.");
            p.setStorageLocationId(loc.getId());
            p.setStorageLocationCode(nz(loc.getCode()));
            p.setZoneId(loc.getZoneId());
            p.setZoneName(nz(loc.getZoneName()));
            p.setSubZoneId(loc.getSubZoneId());
            p.setSubZoneName(nz(loc.getSubZoneName()));
            p.setLocationLabel(!isBlank(loc.getLocationLabel()) ? loc.getLocationLabel() : nz(loc.getName()));
        }
        Product saved = repo.save(p);
        auditService.log("상품관리", "생성", saved.getId(), saved.getCode(), saved.getName(), null, productSummary(saved));
        return saved;
    }

    public Product update(String id, ProductRequest r) {
        Product p = repo.findById(id).orElseThrow(() -> ApiException.notFound("상품을 찾을 수 없습니다."));
        String before = productSummary(p);
        apply(p, r); // code/skuSeq 유지
        Product saved = repo.save(p);
        auditService.log("상품관리", "수정", id, saved.getCode(), saved.getName(), before, productSummary(saved));
        return saved;
    }

    public void remove(String id) {
        Product p = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (p != null) auditService.log("상품관리", "삭제", id, p.getCode(), p.getName(), productSummary(p), null);
    }

    private static String productSummary(Product p) {
        return "name=" + nz(p.getName()) + ", maker=" + nz(p.getMaker())
                + ", price=" + (p.getPrice() == null ? "" : p.getPrice()) + ", path=" + nz(p.getPathLabel());
    }

    private void apply(Product p, ProductRequest r) {
        if (!isBlank(r.name())) p.setName(r.name().trim());
        p.setMaker(nz(r.maker()));
        p.setBarcode(nz(r.barcode()));
        p.setNote(nz(r.note()));
        p.setMainImageUrl(nz(r.mainImageUrl()));
        p.setImages(r.images() != null ? r.images() : new ArrayList<>());
        p.setPrice(r.price() != null ? r.price() : BigDecimal.ZERO);
        // 위치 소속(단지+위치코드)은 생성 시 create()에서 확정하며, 코드가 거기서 채번되므로 수정에서는 바꾸지 않는다.
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
