package com.mpark.wms.sku;

import com.mpark.wms.sku.SkuDtos.*;
import com.mpark.wms.stock.Stock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 재고 동적 검색/집계 — 재고행(stock) x SKU(변형) 조인 기준. 각 행 = (재고행 + 변형) = StockRow.
 * MySQL utf8mb4_*_ci 콜레이션이라 LIKE 는 대소문자 무시.
 */
@Repository
@Transactional(readOnly = true)
public class SkuQueryRepository {

    @PersistenceContext
    private EntityManager em;

    private static final String FROM = " from Stock st, Sku sk where sk.id = st.skuId ";

    private String buildWhere(SkuFilter f, Map<String, Object> p, boolean withSearch) {
        StringBuilder w = new StringBuilder(FROM);
        if (nb(f.complexId()))        { w.append(" and st.complexId = :complexId ");            p.put("complexId", f.complexId()); }
        if (nb(f.storageLocationId())){ w.append(" and st.storageLocationId = :locId ");         p.put("locId", f.storageLocationId()); }
        if (nb(f.categoryId()))       { w.append(" and sk.categoryId = :categoryId ");           p.put("categoryId", f.categoryId()); }
        if (nb(f.productCodeId()))    { w.append(" and sk.productCodeId = :productCodeId ");      p.put("productCodeId", f.productCodeId()); }
        if (nb(f.productDetailId()))  { w.append(" and sk.productDetailId = :productDetailId ");  p.put("productDetailId", f.productDetailId()); }
        if (nb(f.productId()))        { w.append(" and sk.productId = :productId ");              p.put("productId", f.productId()); }
        if (nb(f.skuId()))            { w.append(" and sk.id = :skuId ");                          p.put("skuId", f.skuId()); }
        if (nb(f.status()))           { w.append(" and st.status = :status ");                   p.put("status", f.status()); }
        if (nb(f.color()))            { w.append(" and sk.color = :color ");                     p.put("color", f.color()); }
        if (nb(f.releaseYear()))      { w.append(" and sk.releaseYear = :releaseYear ");         p.put("releaseYear", f.releaseYear()); }
        if (nb(f.productionYear()))   { w.append(" and sk.productionYear = :productionYear ");   p.put("productionYear", f.productionYear()); }
        if (f.priceMin() != null)     { w.append(" and sk.price >= :priceMin ");                 p.put("priceMin", f.priceMin()); }
        if (f.priceMax() != null)     { w.append(" and sk.price <= :priceMax ");                 p.put("priceMax", f.priceMax()); }
        if (Boolean.TRUE.equals(f.lifecycleOnly())) { w.append(" and sk.lifecycleEnabled = true "); }
        if (withSearch && nb(f.search())) {
            w.append(" and (sk.code like :q or sk.productName like :q or sk.spec like :q or sk.pathLabel like :q) ");
            p.put("q", "%" + f.search() + "%");
        }
        return w.toString();
    }

    private String orderBy(String sort) {
        if (sort == null) sort = "recent";
        return switch (sort) {
            case "qtyAsc"      -> " order by st.qty asc ";
            case "qtyDesc"     -> " order by st.qty desc ";
            case "outDesc"     -> " order by st.totalOut desc ";
            case "inDesc"      -> " order by st.totalIn desc ";
            case "nextReplace" -> " order by st.nextReplaceAt asc ";
            case "moved"       -> " order by coalesce(st.lastMovedAt, st.createdAt) desc ";
            case "code"        -> " order by sk.code asc ";
            default            -> " order by st.createdAt desc ";
        };
    }

    public SkuPageResult page(SkuFilter f) {
        Map<String, Object> p = new HashMap<>();
        String where = buildWhere(f, p, true);

        long total = bindAll(em.createQuery("select count(st) " + where, Long.class), p).getSingleResult();

        Object[] agg = bindAll(em.createQuery(
                "select coalesce(sum(st.qty),0), " +
                "coalesce(sum(case when st.status='low' then 1 else 0 end),0), " +
                "coalesce(sum(case when st.status='out' then 1 else 0 end),0) " + where, Object[].class), p).getSingleResult();

        int page = f.page() == null || f.page() < 1 ? 1 : f.page();
        int size = f.pageSize() == null || f.pageSize() < 1 ? 10 : f.pageSize();
        List<Object[]> raw = bindAll(em.createQuery("select st, sk " + where + orderBy(f.sort()), Object[].class), p)
                .setFirstResult((page - 1) * size).setMaxResults(size).getResultList();

        List<StockRow> rows = new ArrayList<>();
        for (Object[] r : raw) rows.add(toRow((Stock) r[0], (Sku) r[1]));
        return new SkuPageResult(rows, total, num(agg[0]), num(agg[1]), num(agg[2]));
    }

    public List<ComplexGroupRow> groupByComplex(SkuFilter f) {
        Map<String, Object> p = new HashMap<>();
        String where = buildWhere(f, p, true);
        List<Object[]> rows = bindAll(em.createQuery(
                "select st.complexName, count(st), coalesce(sum(st.qty),0), " +
                "coalesce(sum(case when st.status='low' then 1 else 0 end),0), " +
                "coalesce(sum(case when st.status='out' then 1 else 0 end),0) " + where +
                " group by st.complexName order by coalesce(sum(st.qty),0) desc", Object[].class), p).getResultList();
        List<ComplexGroupRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            String name = (r[0] == null || ((String) r[0]).isBlank()) ? "미지정" : (String) r[0];
            out.add(new ComplexGroupRow(name, num(r[1]), num(r[2]), num(r[3]), num(r[4])));
        }
        return out;
    }

    public FilterOptions filterOptions() {
        List<String> colors = em.createQuery(
                "select distinct sk.color from Sku sk where sk.color is not null and sk.color <> '' order by sk.color", String.class).getResultList();
        List<String> releaseYears = em.createQuery(
                "select distinct sk.releaseYear from Sku sk where sk.releaseYear is not null and sk.releaseYear <> '' order by sk.releaseYear desc", String.class).getResultList();
        List<String> productionYears = em.createQuery(
                "select distinct sk.productionYear from Sku sk where sk.productionYear is not null and sk.productionYear <> '' order by sk.productionYear desc", String.class).getResultList();
        return new FilterOptions(colors, releaseYears, productionYears);
    }

    public List<StockRow> lifecycleList() {
        List<Object[]> raw = em.createQuery(
                "select st, sk from Stock st, Sku sk where sk.id = st.skuId and sk.lifecycleEnabled = true " +
                "order by coalesce(st.nextReplaceAt, st.createdAt) asc", Object[].class).getResultList();
        List<StockRow> out = new ArrayList<>();
        for (Object[] r : raw) out.add(toRow((Stock) r[0], (Sku) r[1]));
        return out;
    }

    public DashboardSummary dashboardSummary() {
        long complexCount = em.createQuery("select count(c) from Complex c", Long.class).getSingleResult();
        long productCount = em.createQuery("select count(pp) from Product pp", Long.class).getSingleResult();
        long skuCount = em.createQuery("select count(s) from Sku s", Long.class).getSingleResult();
        long totalQty = num(em.createQuery("select coalesce(sum(st.qty),0) from Stock st", Long.class).getSingleResult());
        long lowCount = em.createQuery("select count(st) from Stock st where st.status='low'", Long.class).getSingleResult();
        long outCount = em.createQuery("select count(st) from Stock st where st.status='out'", Long.class).getSingleResult();

        List<Object[]> lowRaw = em.createQuery(
                "select st, sk from Stock st, Sku sk where sk.id = st.skuId and st.status in ('low','out') order by st.qty asc, sk.code asc", Object[].class)
                .setMaxResults(6).getResultList();
        List<StockRow> lowList = new ArrayList<>();
        for (Object[] r : lowRaw) lowList.add(toRow((Stock) r[0], (Sku) r[1]));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime soon = now.plusDays(30);
        long lifeSoon = em.createQuery(
                "select count(st) from Stock st, Sku sk where sk.id = st.skuId and sk.lifecycleEnabled=true and st.nextReplaceAt is not null " +
                "and st.nextReplaceAt >= :now and st.nextReplaceAt < :soon", Long.class)
                .setParameter("now", now).setParameter("soon", soon).getSingleResult();
        long lifeOver = em.createQuery(
                "select count(st) from Stock st, Sku sk where sk.id = st.skuId and sk.lifecycleEnabled=true and st.nextReplaceAt is not null and st.nextReplaceAt < :now", Long.class)
                .setParameter("now", now).getSingleResult();
        List<Object[]> lifeRaw = em.createQuery(
                "select st, sk from Stock st, Sku sk where sk.id = st.skuId and sk.lifecycleEnabled=true and st.nextReplaceAt is not null and st.nextReplaceAt < :soon order by st.nextReplaceAt asc", Object[].class)
                .setParameter("soon", soon).setMaxResults(6).getResultList();
        List<StockRow> lifeList = new ArrayList<>();
        for (Object[] r : lifeRaw) lifeList.add(toRow((Stock) r[0], (Sku) r[1]));

        return new DashboardSummary(complexCount, productCount, skuCount, totalQty, lowCount, outCount,
                lowList, lifeSoon, lifeOver, lifeList);
    }

    private static StockRow toRow(Stock st, Sku sk) {
        return new StockRow(
                st.getId(), sk.getId(), sk.getCode(), sk.getProductId(), sk.getProductName(),
                sk.getSpec(), sk.getColor(), sk.getReleaseYear(), sk.getProductionYear(), sk.getPurpose(),
                sk.getImageUrl(), sk.getProductMainImageUrl(), sk.getPrice(), sk.getSafetyStock(),
                sk.getCategoryId(), sk.getProductCodeId(), sk.getProductDetailId(), sk.getPathLabel(),
                st.getComplexId(), st.getComplexName(),
                st.getStorageLocationId(), st.getStorageLocationCode(), st.getZoneId(), st.getZoneName(),
                st.getSubZoneId(), st.getSubZoneName(), st.getLocationLabel(),
                st.getQty(), st.getTotalIn(), st.getTotalOut(), st.getStatus(),
                st.getLastMovedAt(), st.getLastMovedBy(),
                sk.isLifecycleEnabled(), sk.getCycleValue(), sk.getCycleUnit(),
                st.getLastReplacedAt(), st.getNextReplaceAt(),
                sk.getDimW(), sk.getDimL(), sk.getDimH(), sk.getDimD());
    }

    private static boolean nb(String s) { return s != null && !s.isBlank(); }
    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static <T> TypedQuery<T> bindAll(TypedQuery<T> q, Map<String, Object> p) {
        p.forEach(q::setParameter);
        return q;
    }
}
