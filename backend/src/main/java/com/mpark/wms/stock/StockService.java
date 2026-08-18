package com.mpark.wms.stock;

import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.security.CurrentUser;
import com.mpark.wms.location.StorageLocation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.mpark.wms.movement.*;
import com.mpark.wms.sku.Sku;
import com.mpark.wms.sku.SkuRepository;
import com.mpark.wms.stats.DailyStats;
import com.mpark.wms.stats.DailyStatsRepository;
import com.mpark.wms.stock.StockDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 재고 트랜잭션 — 재고행(stock) 기준. 입고는 (SKU x 위치)로 재고행을 find/create 하고,
 * 출고/조정/이동은 특정 재고행을 잠그고 처리한다. 원장/일별집계/감사로그를 한 트랜잭션으로.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StockService {

    private final StockRepository stockRepo;
    private final SkuRepository skuRepo;
    private final StockMovementRepository movementRepo;
    private final DailyStatsRepository dailyRepo;
    private final LifecycleLogRepository lifecycleLogRepo;
    private final LocationLogRepository locationLogRepo;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final IdempotencyKeyRepository idemRepo;
    @PersistenceContext private EntityManager em;

    /* ===================== 입고 ===================== */
    public StockResult inbound(InboundRequest r) {
        if (!currentUser.canStock()) throw ApiException.forbidden("입/출고 권한이 없습니다. 관리자에게 문의하세요.");
        StockResult dup = idemLookup(r.requestId());
        if (dup != null) return dup; // 중복 요청 — 이미 반영됨
        int val = nz(r.qty());
        if (val <= 0) throw ApiException.badRequest("수량을 올바르게 입력하세요.");
        if (isBlank(r.skuId())) throw ApiException.badRequest("SKU를 선택하세요.");

        Sku sku = skuRepo.findById(r.skuId()).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));

        // SKU=단일 위치: SKU의 유일한 재고행에 입고. 없으면(레거시) 지정 위치로 생성.
        Stock st = stockRepo.findBySkuIdForUpdate(sku.getId()).orElse(null);
        if (st == null) {
            if (isBlank(r.storageLocationId()))
                throw ApiException.badRequest("이 SKU의 재고 위치가 없습니다. 상품에 위치코드를 지정하거나 SKU를 다시 등록하세요.");
            st = newStock(sku, loc(r.storageLocationId()));
        }

        String actor = currentUser.name();
        int before = st.getQty();
        int after = before + val;
        if (st.getId() == null && st.getInitialQty() == 0) st.setInitialQty(val);
        st.setQty(after);
        st.setStatus(status(after, sku.getSafetyStock()));
        st.setTotalIn(st.getTotalIn() + val);
        st.setLastMovedBy(actor);
        st.setLastMovedAt(LocalDateTime.now());
        // 연한관리 SKU면 최초 입고 시 교체 일정 초기화 (기준일=오늘, 다음예정=오늘+주기)
        if (sku.isLifecycleEnabled() && st.getNextReplaceAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            st.setLastReplacedAt(now);
            st.setNextReplaceAt(nextReplaceAt(sku, now));
            st.setLastReplacedBy(actor);
        }
        stockRepo.save(st);

        StockMovement mv = saveMovement(st, sku, "in", val, val, before, after, r.memo(), r.reason(), null);
        if (r.unitPrice() != null) mv.setUnitPrice(r.unitPrice()); // 입고 실구매단가(백오피스 입고에서만)
        bumpDaily("in", val, +1);
        auditService.log("입/출고관리", "입고", sku.getId(),
                sku.getCode() + " @" + nz2(st.getComplexName()) + " (" + before + "→" + after + ")", sku.getProductName());
        idemStore(r.requestId(), before, after, val);
        return new StockResult(before, after, val);
    }

    /* ===================== 출고 ===================== */
    public StockResult outbound(OutboundRequest r) {
        if (!currentUser.canStock()) throw ApiException.forbidden("입/출고 권한이 없습니다. 관리자에게 문의하세요.");
        StockResult dup = idemLookup(r.requestId());
        if (dup != null) return dup; // 중복 요청 — 이미 반영됨
        int val = nz(r.qty());
        if (val <= 0) throw ApiException.badRequest("수량을 올바르게 입력하세요.");
        Stock st = stockRepo.findByIdForUpdate(r.stockId())
                .orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        Sku sku = skuRepo.findById(st.getSkuId()).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        int before = st.getQty();
        if (before < val) throw ApiException.badRequest("재고 부족: 현재 " + before + "개");
        int after = before - val;
        String actor = currentUser.name();
        st.setQty(after);
        st.setStatus(status(after, sku.getSafetyStock()));
        st.setTotalOut(st.getTotalOut() + val);
        st.setLastMovedBy(actor);
        st.setLastMovedAt(LocalDateTime.now());

        StockMovement mv = saveMovement(st, sku, "out", val, -val, before, after, r.memo(), r.reason(), null);
        mv.setUsagePlace(nz(r.usagePlace()));
        mv.setRequestDept(nz(r.requestDept()));
        mv.setRequester(nz(r.requester()));
        mv.setHandler(nz(r.handler()));
        bumpDaily("out", val, +1);
        auditService.log("입/출고관리", "출고", sku.getId(),
                sku.getCode() + " @" + nz2(st.getComplexName()) + " (" + before + "→" + after + ")", sku.getProductName());
        idemStore(r.requestId(), before, after, -val);
        return new StockResult(before, after, -val);
    }

    /* ===================== 조정 / 실사 ===================== */
    public StockResult adjust(AdjustRequest r) {
        if (!currentUser.canManage()) throw ApiException.forbidden("재고조정/실사 권한이 없습니다.");
        StockResult dup = idemLookup(r.requestId());
        if (dup != null) return dup; // 중복 요청 — 이미 반영됨
        String type = "audit".equals(r.type()) ? "audit" : "adjust";
        int val = nz(r.value());
        if (val < 0) throw ApiException.badRequest("수량을 올바르게 입력하세요.");
        Stock st = stockRepo.findByIdForUpdate(r.stockId())
                .orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        Sku sku = skuRepo.findById(st.getSkuId()).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        int before = st.getQty();
        int after = val;
        int delta = after - before;
        String actor = currentUser.name();
        st.setQty(after);
        st.setStatus(status(after, sku.getSafetyStock()));
        st.setLastMovedBy(actor);
        st.setLastMovedAt(LocalDateTime.now());

        // 실사면 마지막 실사 스냅샷/상태 갱신 (오차 0=정상, 오차≠0=비정상. 이전 정상처리 이력은 초기화)
        if ("audit".equals(type)) {
            st.setLastAuditedAt(LocalDateTime.now());
            st.setLastAuditedBy(actor);
            st.setLastAuditCounted(after);
            st.setLastAuditDiff(delta);
            st.setAuditStatus(delta == 0 ? "ok" : "mismatch");
            st.setAuditResolvedAt(null);
            st.setAuditResolvedBy(null);
            st.setAuditResolveReason("");
        }

        saveMovement(st, sku, type, Math.abs(delta), delta, before, after, r.memo(), r.reason(), null);
        bumpDaily(type, Math.abs(delta), +1);
        auditService.log("재고관리", "audit".equals(type) ? "재고실사" : "재고조정", sku.getId(),
                sku.getCode() + " (" + before + "→" + after + ")", sku.getProductName());
        idemStore(r.requestId(), before, after, delta);
        return new StockResult(before, after, delta);
    }

    /* ===================== 실사 오차 정상처리 ===================== */
    public void resolveAudit(String stockId, String reason) {
        if (!currentUser.canManage()) throw ApiException.forbidden("실사 정상처리 권한이 없습니다.");
        if (isBlank(reason)) throw ApiException.badRequest("정상처리 사유를 입력하세요.");
        Stock st = stockRepo.findByIdForUpdate(stockId)
                .orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        if (!"mismatch".equals(st.getAuditStatus())) throw ApiException.badRequest("정상처리할 실사 오차가 없습니다.");
        Sku sku = skuRepo.findById(st.getSkuId()).orElse(null);
        String actor = currentUser.name();
        st.setAuditStatus("ok");
        st.setAuditResolvedAt(LocalDateTime.now());
        st.setAuditResolvedBy(actor);
        st.setAuditResolveReason(reason);

        Integer d = st.getLastAuditDiff();
        String diffText = d == null ? "" : (d > 0 ? "+" : "") + d;
        auditService.log("재고관리", "실사 정상처리", st.getSkuId(),
                (sku == null ? "" : sku.getCode()) + " 오차 " + diffText + " · " + reason,
                sku == null ? "" : sku.getProductName());
    }

    /* ===================== 재고이동 (SKU 단일 재고행을 도착 위치로 relocate) ===================== */
    public TransferResult transfer(TransferRequest r) {
        if (!currentUser.canManage()) throw ApiException.forbidden("재고이동 권한이 없습니다. 관리자에게 문의하세요.");
        if (!isBlank(r.requestId()) && idemRepo.existsById(r.requestId()))
            throw ApiException.badRequest("이미 처리된 이동 요청입니다. 재고를 새로고침해 확인하세요.");
        if (isBlank(r.toStorageLocationId())) throw ApiException.badRequest("도착 위치코드를 지정하세요.");

        // 출발 재고행: stockId 우선, 없으면 skuId 로 그 SKU의 유일한 행을 잠근다.
        Stock st = !isBlank(r.stockId())
                ? stockRepo.findByIdForUpdate(r.stockId()).orElseThrow(() -> ApiException.notFound("출발 재고를 찾을 수 없습니다."))
                : stockRepo.findBySkuIdForUpdate(r.skuId()).orElseThrow(() -> ApiException.notFound("출발 재고를 찾을 수 없습니다."));
        Sku sku = skuRepo.findById(st.getSkuId()).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        StorageLocation toLoc = loc(r.toStorageLocationId());
        String actor = currentUser.name();
        LocalDateTime now = LocalDateTime.now();

        if (Objects.equals(st.getStorageLocationId(), toLoc.getId()))
            throw ApiException.badRequest("출발지와 도착지가 같습니다.");

        int qty = st.getQty();
        String fromLabel = locLabel(st);

        // relocate: 재고행의 위치필드만 도착지로 변경(수량 그대로, 코드·재고행 식별자 불변)
        st.setComplexId(toLoc.getComplexId());
        st.setComplexName(nz(toLoc.getComplexName()));
        st.setStorageLocationId(toLoc.getId());
        st.setStorageLocationCode(nz(toLoc.getCode()));
        st.setZoneId(toLoc.getZoneId());
        st.setZoneName(nz(toLoc.getZoneName()));
        st.setSubZoneId(toLoc.getSubZoneId());
        st.setSubZoneName(nz(toLoc.getSubZoneName()));
        st.setLocationLabel(!isBlank(toLoc.getLocationLabel()) ? toLoc.getLocationLabel() : nz(toLoc.getName()));
        st.setLastMovedBy(actor);
        st.setLastMovedAt(now);
        stockRepo.save(st);

        String transferId = UUID.randomUUID().toString();
        String toLabel = locLabel(st);

        // 원장: 수량변화 없는 위치이동(delta 0, type=move) — 입출고통합조회에 노출
        StockMovement mv = saveMovement(st, sku, "move", qty, 0, qty, qty,
                r.memo(), isBlank(r.reason()) ? "재고이동" : r.reason(), transferId);
        mv.setPathLabel(fromLabel + " → " + toLabel);

        // 위치 이동 이력
        LocationLog log = new LocationLog();
        log.setSkuId(sku.getId());
        log.setSkuCode(sku.getCode());
        log.setProductName(sku.getProductName());
        log.setFromLabel(fromLabel);
        log.setToLabel(toLabel);
        log.setStorageLocationId(st.getStorageLocationId());
        log.setStorageLocationCode(st.getStorageLocationCode());
        log.setLocationLabel(st.getLocationLabel());
        log.setComplexName(st.getComplexName());
        log.setByUserId(currentUser.id());
        log.setByName(actor);
        locationLogRepo.save(log);

        auditService.log("입/출고관리", "재고이동", sku.getId(),
                sku.getCode() + " " + fromLabel + " → " + toLabel + (qty > 0 ? " " + qty + "개" : ""), sku.getProductName());
        idemStore(r.requestId(), qty, qty, 0);
        return new TransferResult(transferId, st.getId(), st.getId(), qty, true);
    }

    /* ===================== 취소(역분개) ===================== */
    public StockResult voidMovement(String movementId, String reason) {
        StockMovement m = movementRepo.findByIdForUpdate(movementId)
                .orElseThrow(() -> ApiException.notFound("이력을 찾을 수 없습니다."));
        if ("void".equals(m.getType())) throw ApiException.badRequest("취소 전표는 다시 취소할 수 없습니다.");
        if (m.isVoided()) throw ApiException.badRequest("이미 취소된 처리입니다.");
        String uid = currentUser.id();
        if (uid != null && !Objects.equals(uid, m.getByUserId()))
            throw ApiException.forbidden("본인이 등록한 처리만 취소할 수 있습니다. 관리자에게 정정을 요청하세요.");
        if (m.getAt() == null || !m.getAt().toLocalDate().isEqual(LocalDate.now()))
            throw ApiException.badRequest("당일 처리분만 취소할 수 있습니다. 관리자에게 정정을 요청하세요.");
        if (isBlank(m.getStockId())) throw ApiException.badRequest("이 이력은 취소할 수 없습니다.");

        Stock st = stockRepo.findByIdForUpdate(m.getStockId())
                .orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        Sku sku = skuRepo.findById(st.getSkuId()).orElse(null);
        int revDelta = -m.getDelta();
        int before = st.getQty();
        int after = before + revDelta;
        if (after < 0) throw ApiException.badRequest("그 사이 재고가 변경되어 취소할 수 없습니다. 관리자에게 정정을 요청하세요.");

        String actor = currentUser.name();
        st.setQty(after);
        st.setStatus(status(after, sku == null ? 0 : sku.getSafetyStock()));
        if ("in".equals(m.getType())) st.setTotalIn(Math.max(0, st.getTotalIn() - m.getQty()));
        if ("out".equals(m.getType())) st.setTotalOut(Math.max(0, st.getTotalOut() - m.getQty()));
        st.setLastMovedBy(actor);
        st.setLastMovedAt(LocalDateTime.now());

        m.setVoided(true);
        m.setVoidedAt(LocalDateTime.now());
        m.setVoidedBy(actor);
        m.setVoidReason(nz(reason));

        StockMovement v = new StockMovement();
        v.setStockId(st.getId());
        v.setSkuId(st.getSkuId());
        v.setSkuCode(m.getSkuCode());
        v.setProductName(m.getProductName());
        v.setType("void");
        v.setQty(Math.abs(revDelta));
        v.setDelta(revDelta);
        v.setBeforeQty(before);
        v.setAfterQty(after);
        v.setComplexId(st.getComplexId());
        v.setComplexName(st.getComplexName());
        v.setPathLabel(locLabel(st));
        v.setReason(isBlank(reason) ? "취소" : reason);
        v.setByUserId(currentUser.id());
        v.setByName(actor);
        v.setReversalOf(m.getId());
        movementRepo.save(v);

        bumpDaily(m.getType(), m.getQty(), -1);
        return new StockResult(before, after, revDelta);
    }

    /* ===================== 연한 교체 (재고행) ===================== */
    public LifecycleResult replaceLifecycle(String stockId, String reason) {
        Stock st = stockRepo.findByIdForUpdate(stockId)
                .orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        Sku sku = skuRepo.findById(st.getSkuId()).orElseThrow(() -> ApiException.notFound("SKU를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = nextReplaceAt(sku, now);
        String actor = currentUser.name();
        st.setLastReplacedAt(now);
        st.setNextReplaceAt(next);
        st.setLastReplacedBy(actor);

        LifecycleLog l = new LifecycleLog();
        l.setSkuId(sku.getId());
        l.setSkuCode(sku.getCode());
        l.setProductName(sku.getProductName());
        l.setReplacedAt(now);
        l.setNextReplaceAt(next);
        l.setReason(!isBlank(reason) ? reason : nz(sku.getReplaceReason()));
        l.setPathLabel(locLabel(st));
        l.setComplexName(st.getComplexName());
        l.setByUserId(currentUser.id());
        l.setByName(actor);
        lifecycleLogRepo.save(l);

        auditService.log("재고관리", "교체", sku.getId(), sku.getCode(), sku.getProductName());
        return new LifecycleResult(now, next);
    }

    public void verifyLocation(String stockId, String name) {
        Stock st = stockRepo.findById(stockId).orElseThrow(() -> ApiException.notFound("재고를 찾을 수 없습니다."));
        st.setLocationVerifiedAt(LocalDateTime.now());
        st.setLocationVerifiedBy(!isBlank(name) ? name : currentUser.name());
    }

    /* ===================== 조회 ===================== */
    @Transactional(readOnly = true)
    public List<StockMovement> listMovements(String skuId, int max) {
        return movementRepo.findBySkuIdOrderByAtDesc(skuId, PageRequest.of(0, max));
    }

    @Transactional(readOnly = true)
    public List<StockMovement> recentMovements(int max) {
        return movementRepo.findAllByOrderByAtDesc(PageRequest.of(0, max));
    }

    @Transactional(readOnly = true)
    public List<StockMovement> movementsByDate(LocalDate date, int max) {
        return movementRepo.findByAtBetweenOrderByAtDesc(date.atStartOfDay(), date.plusDays(1).atStartOfDay(), PageRequest.of(0, max));
    }

    @Transactional(readOnly = true)
    public List<LifecycleLog> listLifecycleLogs(String skuId, int max) {
        return lifecycleLogRepo.findBySkuIdOrderByAtDesc(skuId, PageRequest.of(0, max));
    }

    @Transactional(readOnly = true)
    public List<LocationLog> listLocationLogs(String skuId, int max) {
        return locationLogRepo.findBySkuIdOrderByAtDesc(skuId, PageRequest.of(0, max));
    }

    /* ---------- helpers ---------- */
    private StorageLocation loc(String id) {
        StorageLocation l = (id == null) ? null : em.find(StorageLocation.class, id);
        if (l == null) throw ApiException.badRequest("보관위치를 찾을 수 없습니다.");
        return l;
    }

    private Stock newStock(Sku sku, StorageLocation loc) {
        Stock st = new Stock();
        st.setSkuId(sku.getId());
        st.setComplexId(loc.getComplexId());
        st.setComplexName(nz(loc.getComplexName()));
        st.setStorageLocationId(loc.getId());
        st.setStorageLocationCode(nz(loc.getCode()));
        st.setZoneId(loc.getZoneId());
        st.setZoneName(nz(loc.getZoneName()));
        st.setSubZoneId(loc.getSubZoneId());
        st.setSubZoneName(nz(loc.getSubZoneName()));
        st.setLocationLabel(!isBlank(loc.getLocationLabel()) ? loc.getLocationLabel() : nz(loc.getName()));
        return st;
    }

    private StockMovement saveMovement(Stock st, Sku sku, String type, int qty, int delta, int before, int after,
                              String memo, String reason, String transferId) {
        StockMovement m = new StockMovement();
        m.setStockId(st.getId());
        m.setSkuId(sku.getId());
        m.setSkuCode(sku.getCode());
        m.setProductName(sku.getProductName());
        m.setType(type);
        m.setQty(qty);
        m.setDelta(delta);
        m.setBeforeQty(before);
        m.setAfterQty(after);
        m.setComplexId(st.getComplexId());
        m.setComplexName(st.getComplexName());
        m.setPathLabel(locLabel(st));
        m.setMemo(nz(memo));
        m.setReason(nz(reason));
        m.setByUserId(currentUser.id());
        m.setByName(currentUser.name());
        m.setTransferId(transferId);
        return movementRepo.save(m);
    }

    private void bumpDaily(String type, int qty, int sign) {
        LocalDate today = LocalDate.now();
        DailyStats d = dailyRepo.findById(today).orElseGet(() -> {
            DailyStats n = new DailyStats();
            n.setStatDate(today);
            return n;
        });
        d.setMoveCount(Math.max(0, d.getMoveCount() + sign));
        switch (type) {
            case "in" -> { d.setInCount(Math.max(0, d.getInCount() + sign)); d.setInQty(Math.max(0, d.getInQty() + sign * qty)); }
            case "out" -> { d.setOutCount(Math.max(0, d.getOutCount() + sign)); d.setOutQty(Math.max(0, d.getOutQty() + sign * qty)); }
            case "adjust" -> d.setAdjustCount(Math.max(0, d.getAdjustCount() + sign));
            case "audit" -> d.setAuditCount(Math.max(0, d.getAuditCount() + sign));
            default -> { }
        }
        d.setUpdatedAt(LocalDateTime.now());
        dailyRepo.save(d);
    }

    private static String locLabel(Stock st) {
        String base = nz2(st.getComplexName());
        if (!isBlank(st.getLocationLabel())) return base.isBlank() ? st.getLocationLabel() : base + " > " + st.getLocationLabel();
        return base;
    }

    /** 연한 주기로 다음 교체 예정일 계산 (연한 미사용/주기 0이면 null) */
    private static LocalDateTime nextReplaceAt(Sku sku, LocalDateTime from) {
        if (!sku.isLifecycleEnabled()) return null;
        int v = sku.getCycleValue() == null ? 0 : sku.getCycleValue();
        if (v <= 0) return null;
        return switch (sku.getCycleUnit() == null ? "month" : sku.getCycleUnit().toLowerCase()) {
            case "day" -> from.plusDays(v);
            case "year" -> from.plusYears(v);
            default -> from.plusMonths(v);
        };
    }

    static String status(int qty, int safety) {
        if (qty <= 0) return "out";
        if (safety > 0 && qty <= safety) return "low";
        return "in_stock";
    }

    /* ---------- 멱등 처리 ---------- */
    /** 같은 request_id 로 이미 처리됐으면 그때의 결과를 반환(없으면 null) */
    private StockResult idemLookup(String rid) {
        if (isBlank(rid)) return null;
        return idemRepo.findById(rid)
                .map(k -> new StockResult(nz(k.getBeforeQty()), nz(k.getAfterQty()), nz(k.getDelta())))
                .orElse(null);
    }
    /** 처리 결과를 request_id 로 기록(중복 재요청 시 재적용 방지). 같은 트랜잭션에서 저장 */
    private void idemStore(String rid, int before, int after, int delta) {
        if (isBlank(rid)) return;
        IdempotencyKey k = new IdempotencyKey();
        k.setRequestId(rid);
        k.setBeforeQty(before);
        k.setAfterQty(after);
        k.setDelta(delta);
        idemRepo.save(k);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nz2(String s) { return s == null ? "" : s; }
    private static int nz(Integer v) { return v == null ? 0 : v; }
}
