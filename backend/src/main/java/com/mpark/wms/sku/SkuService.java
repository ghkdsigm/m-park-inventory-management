package com.mpark.wms.sku;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import com.mpark.wms.movement.StockMovementRepository;
import com.mpark.wms.product.Product;
import com.mpark.wms.product.ProductRepository;
import com.mpark.wms.sku.SkuDtos.*;
import com.mpark.wms.stock.Stock;
import com.mpark.wms.stock.StockRepository;
import com.mpark.wms.stock.StockService;
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
    private final StockRepository stockRepo;
    private final StockMovementRepository movementRepo;
    private final AuditService auditService;
    private final CodeGenerator codeGenerator;

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
    public SkuListPageResult managePage(SkuFilter f) { return queryRepo.managePage(f); }

    @Transactional(readOnly = true)
    public SkuAggPageResult pageBySku(SkuFilter f) { return queryRepo.pageBySku(f); }

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
        // SKU 코드 = {상품코드}-{고유번호}. 고유번호는 (단지×카테고리)별 1부터, 패딩 없음.
        long uid = codeGenerator.nextValue("skuuid:" + nz(product.getComplexId()) + ":" + nz(product.getCategoryId()));
        s.setCode(product.getCode() + "-" + uid);
        s.setQrGenerated(true);
        apply(s, r, product);
        // 위치소속: SKU 는 상품의 단지+위치코드를 상속(요청값보다 상품이 우선)
        if (!isBlank(product.getComplexId())) {
            s.setComplexId(product.getComplexId());
            s.setComplexName(nz(product.getComplexName()));
        }
        Sku saved = repo.save(s);
        // SKU=단일 위치: 상품 위치에 재고행(qty 0) 1개 자동 생성. 이후 입고/이동은 이 행을 대상으로 한다.
        if (!isBlank(product.getStorageLocationId())) {
            Stock st = new Stock();
            st.setSkuId(saved.getId());
            st.setComplexId(product.getComplexId());
            st.setComplexName(nz(product.getComplexName()));
            st.setStorageLocationId(product.getStorageLocationId());
            st.setStorageLocationCode(nz(product.getStorageLocationCode()));
            st.setZoneId(product.getZoneId());
            st.setZoneName(nz(product.getZoneName()));
            st.setSubZoneId(product.getSubZoneId());
            st.setSubZoneName(nz(product.getSubZoneName()));
            st.setLocationLabel(nz(product.getLocationLabel()));
            st.setQty(0);
            st.setStatus("out");
            stockRepo.save(st);
        }
        auditService.log("SKU관리", "생성", saved.getId(), saved.getCode(), saved.getProductName(), null, skuSummary(saved));
        return saved;
    }

    public Sku update(String id, SkuRequest r) {
        Sku s = repo.findById(id).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        String before = skuSummary(s);
        int oldSafety = s.getSafetyStock();
        apply(s, r, null);
        Sku saved = repo.save(s);
        // 안전재고가 바뀌면 재고행 상태(정상/부족/품절)를 즉시 재계산한다.
        // (상태는 원래 입고/출고/조정 등 재고 이동 때만 갱신되므로, 안전재고만 수정하면 낡은 상태가 남는다.)
        if (saved.getSafetyStock() != oldSafety) {
            for (Stock st : stockRepo.findBySkuId(id)) {
                st.setStatus(StockService.status(st.getQty(), saved.getSafetyStock()));
                stockRepo.save(st);
            }
        }
        auditService.log("SKU관리", "수정", id, saved.getCode(), saved.getProductName(), before, skuSummary(saved));
        return saved;
    }

    private static String skuSummary(Sku s) {
        return "spec=" + nz(s.getSpec()) + ", color=" + nz(s.getColor())
                + ", price=" + (s.getPrice() == null ? "" : s.getPrice()) + ", safety=" + s.getSafetyStock();
    }

    public void remove(String id) {
        // 재고/원장 보존: 재고행이나 입출고 이력이 있으면 삭제 금지 (FK CASCADE 로 인한 이력 전멸 방지)
        if (stockRepo.existsBySkuId(id))
            throw ApiException.badRequest("재고가 있는 SKU는 삭제할 수 없습니다. 먼저 출고/조정으로 재고를 정리하세요.");
        if (movementRepo.existsBySkuId(id))
            throw ApiException.badRequest("입출고 이력이 있는 SKU는 삭제할 수 없습니다. 이력 보존을 위해 삭제가 제한됩니다.");
        Sku s = repo.findById(id).orElse(null);
        repo.deleteById(id);
        if (s != null) auditService.log("SKU관리", "삭제", id, s.getCode(), s.getProductName(), skuSummary(s), null);
    }

    private void apply(Sku s, SkuRequest r, Product product) {
        s.setProductId(r.productId() != null ? r.productId() : s.getProductId());
        if (r.complexId() != null) s.setComplexId(r.complexId());
        if (r.complexName() != null) s.setComplexName(nz(r.complexName()));
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
