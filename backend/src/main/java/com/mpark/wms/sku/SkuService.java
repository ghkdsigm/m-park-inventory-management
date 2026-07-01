package com.mpark.wms.sku;

import com.mpark.wms.common.ApiException;
import com.mpark.wms.product.Product;
import com.mpark.wms.product.ProductRepository;
import com.mpark.wms.sku.SkuDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * SKU(변형) 서비스 — 상품/속성/치수/표준가/연한설정/분류만 관리한다.
 * 실제 재고(수량·단지·위치·이동)는 StockService/재고행이 담당하며, 조회/집계는 SkuQueryRepository(재고행 기준).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SkuService {

    private final SkuRepository repo;
    private final ProductRepository productRepo;
    private final SkuQueryRepository queryRepo;

    /* ---------- 단순 조회 ---------- */
    @Transactional(readOnly = true)
    public List<Sku> list() { return repo.findAllByOrderByCreatedAtDesc(); }

    @Transactional(readOnly = true)
    public List<Sku> listByProduct(String productId) { return repo.findByProductIdOrderByCodeAsc(productId); }

    @Transactional(readOnly = true)
    public List<Sku> listByIds(Collection<String> ids) {
        return (ids == null || ids.isEmpty()) ? List.of() : repo.findByIdIn(ids);
    }

    @Transactional(readOnly = true)
    public Sku get(String id) { return repo.findById(id).orElse(null); }

    @Transactional(readOnly = true)
    public Sku getByCode(String code) { return repo.findByCode(code).orElse(null); }

    /* ---------- 집계/검색 위임 (재고행 기준) ---------- */
    @Transactional(readOnly = true)
    public SkuPageResult page(SkuFilter f) { return queryRepo.page(f); }

    @Transactional(readOnly = true)
    public List<ComplexGroupRow> groupByComplex(SkuFilter f) { return queryRepo.groupByComplex(f); }

    @Transactional(readOnly = true)
    public FilterOptions filterOptions() { return queryRepo.filterOptions(); }

    @Transactional(readOnly = true)
    public DashboardSummary dashboardSummary() { return queryRepo.dashboardSummary(); }

    @Transactional(readOnly = true)
    public List<StockRow> listLifecycle() { return queryRepo.lifecycleList(); }

    /* ---------- 생성/수정 (변형만; 재고는 입고에서) ---------- */
    public Sku create(SkuRequest r) {
        if (isBlank(r.productId())) throw ApiException.badRequest("상품을 선택하세요.");
        Product product = productRepo.findByIdForUpdate(r.productId())
                .orElseThrow(() -> ApiException.badRequest("상품을 찾을 수 없습니다."));
        int seq = product.getSkuSeq() + 1;
        product.setSkuSeq(seq);
        Sku s = new Sku();
        s.setCode(product.getCode() + "-" + String.format("%03d", seq));
        s.setQrGenerated(true);
        apply(s, r, product);
        return repo.save(s);
    }

    public Sku update(String id, SkuRequest r) {
        Sku s = repo.findById(id).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        apply(s, r, null);
        return repo.save(s);
    }

    public void remove(String id) { repo.deleteById(id); }

    private void apply(Sku s, SkuRequest r, Product product) {
        s.setProductId(r.productId() != null ? r.productId() : s.getProductId());
        s.setProductName(nz(r.productName()));
        s.setSpec(nz(r.spec()));
        s.setColor(nz(r.color()));
        s.setReleaseYear(nz(r.releaseYear()));
        s.setProductionYear(nz(r.productionYear()));
        s.setPurpose(nz(r.purpose()));
        s.setImageUrl(nz(r.imageUrl()));
        s.setProductMainImageUrl(nz(r.productMainImageUrl()));
        BigDecimal price = r.price();
        if ((price == null || price.signum() == 0) && product != null) price = product.getPrice();
        if (price != null) s.setPrice(price);
        if (r.safetyStock() != null) s.setSafetyStock(r.safetyStock());
        s.setCategoryId(r.categoryId());
        s.setProductCodeId(r.productCodeId());
        s.setProductDetailId(r.productDetailId());
        s.setPathLabel(nz(r.pathLabel()));
        s.setLifecycleEnabled(Boolean.TRUE.equals(r.lifecycleEnabled()));
        if (r.cycleValue() != null) s.setCycleValue(r.cycleValue());
        if (r.cycleUnit() != null) s.setCycleUnit(r.cycleUnit());
        s.setReplaceReason(nz(r.replaceReason()));
        s.setLifecycleNote(nz(r.lifecycleNote()));
        s.setDimW(r.dimW());
        s.setDimL(r.dimL());
        s.setDimH(r.dimH());
        s.setDimD(r.dimD());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
}
