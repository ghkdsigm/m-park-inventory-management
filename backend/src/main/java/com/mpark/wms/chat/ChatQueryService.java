package com.mpark.wms.chat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * AI 챗봇 "도구(tool)" 실행용 조회 서비스.
 * <p>챗봇 스트리밍 본문은 {@link ChatService#prepareChat} 의 트랜잭션 밖(별도 스레드)에서 실행되므로,
 * 도구 호출 시점의 DB 조회는 이 빈의 {@code @Transactional} 메서드를 통해 각기 독립된 읽기 트랜잭션에서 수행한다.
 * (SKU 카탈로그를 프롬프트에 통째로 넣지 않고, 필요한 후보만 그때그때 조회 → 재고 수가 커져도 안전.)</p>
 */
@Service
public class ChatQueryService {

    @PersistenceContext
    private EntityManager em;

    /** SKU 검색: 코드/상품명/규격/색상/분류경로 부분일치. 각 SKU에 현재 재고행(위치·수량)을 함께 반환. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchSku(String query, int limit) {
        int cap = clamp(limit, 1, 30);
        String like = "%" + safeLower(query) + "%";

        List<Object[]> rows = em.createQuery(
                "SELECT s.id, s.code, s.productName, s.spec, s.color, s.pathLabel, s.price, s.safetyStock, s.complexName " +
                "FROM Sku s WHERE LOWER(s.code) LIKE :q OR LOWER(s.productName) LIKE :q " +
                "OR LOWER(s.spec) LIKE :q OR LOWER(s.color) LIKE :q OR LOWER(s.pathLabel) LIKE :q " +
                "ORDER BY s.code", Object[].class)
                .setParameter("q", like).setMaxResults(cap).getResultList();
        if (rows.isEmpty()) return List.of();

        List<String> ids = new ArrayList<>();
        for (Object[] r : rows) ids.add(String.valueOf(r[0]));

        // 대상 SKU들의 현재 재고행(수량>0)
        List<Object[]> st = em.createQuery(
                "SELECT st.skuId, st.id, st.storageLocationId, st.complexName, st.locationLabel, st.storageLocationCode, st.qty " +
                "FROM Stock st WHERE st.skuId IN :ids AND st.qty > 0 ORDER BY st.qty DESC", Object[].class)
                .setParameter("ids", ids).getResultList();

        Map<String, List<Map<String, Object>>> stById = new HashMap<>();
        Map<String, Integer> totalById = new HashMap<>();
        for (Object[] r : st) {
            String sid = String.valueOf(r[0]);
            int qty = ((Number) r[6]).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stockId", String.valueOf(r[1]));
            m.put("storageLocationId", r[2] == null ? null : String.valueOf(r[2]));
            m.put("location", locLabel(r[3], r[4], r[5]));
            m.put("qty", qty);
            stById.computeIfAbsent(sid, k -> new ArrayList<>()).add(m);
            totalById.merge(sid, qty, Integer::sum);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            String id = String.valueOf(r[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", id);
            m.put("code", r[1]);
            m.put("productName", r[2]);
            m.put("spec", nz(r[3]));
            m.put("color", nz(r[4]));
            m.put("pathLabel", nz(r[5]));
            m.put("price", r[6]);
            m.put("safetyStock", r[7]);
            m.put("complexName", nz(r[8]));
            m.put("totalQty", totalById.getOrDefault(id, 0));
            m.put("stocks", stById.getOrDefault(id, List.of())); // 출고·이동 시 stockId 로 사용
            out.add(m);
        }
        return out;
    }

    /** 보관위치 검색: 코드/이름/단지/구역/상세구역/위치경로 부분일치. 입고 대상 위치 선택용.
     *  보관위치는 개수가 적은 마스터 데이터라 사실상 전부 반환한다(과도 방지용 500 상한만). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchLocation(String query, int limit) {
        int cap = clamp(limit, 1, 500);
        String like = "%" + safeLower(query) + "%";

        List<Object[]> rows = em.createQuery(
                "SELECT l.id, l.code, l.name, l.complexName, l.zoneName, l.subZoneName " +
                "FROM StorageLocation l WHERE LOWER(l.code) LIKE :q OR LOWER(l.name) LIKE :q " +
                "OR LOWER(l.complexName) LIKE :q OR LOWER(l.zoneName) LIKE :q " +
                "OR LOWER(l.subZoneName) LIKE :q OR LOWER(l.locationLabel) LIKE :q " +
                "ORDER BY l.code", Object[].class)
                .setParameter("q", like).setMaxResults(cap).getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            // 경로는 항상 '단지 › 구역 › 상세구역 › 위치이름(잎)'으로 조립.
            // (locationLabel 컬럼은 행마다 잎만/전체경로가 섞여 있어 중복 표기를 유발하므로 사용하지 않음)
            String label = nz(r[3]);                                    // complexName
            if (!nz(r[4]).isBlank()) label = joinPath(label, nz(r[4])); // zoneName
            if (!nz(r[5]).isBlank()) label = joinPath(label, nz(r[5])); // subZoneName
            if (!nz(r[2]).isBlank()) label = joinPath(label, nz(r[2])); // name (잎)
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("storageLocationId", String.valueOf(r[0]));
            m.put("code", r[1]);
            m.put("name", nz(r[2]));
            m.put("location", label);
            out.add(m);
        }
        return out;
    }

    /** 전체 재고 현황 요약: 총 SKU 수·재고보유 SKU 수·총수량·품절 수·재고부족 목록(총재고 &lt; 안전재고). */
    @Transactional(readOnly = true)
    public Map<String, Object> stockOverview() {
        long totalSku = ((Number) em.createQuery("SELECT COUNT(s) FROM Sku s").getSingleResult()).longValue();

        // SKU별 총재고(수량>0) 집계
        List<Object[]> totals = em.createQuery(
                "SELECT st.skuId, SUM(st.qty) FROM Stock st WHERE st.qty > 0 GROUP BY st.skuId", Object[].class)
                .getResultList();
        Map<String, Long> totalBySku = new HashMap<>();
        long totalQty = 0;
        for (Object[] r : totals) {
            long q = ((Number) r[1]).longValue();
            totalBySku.put(String.valueOf(r[0]), q);
            totalQty += q;
        }
        long skusWithStock = totalBySku.size();
        long outOfStock = totalSku - skusWithStock; // 총재고 0 인 SKU = 품절

        // 재고부족: 안전재고>0 이면서 총재고 < 안전재고
        List<Object[]> safetySkus = em.createQuery(
                "SELECT s.id, s.code, s.productName, s.safetyStock FROM Sku s WHERE s.safetyStock > 0 ORDER BY s.code",
                Object[].class).getResultList();
        int lowCount = 0;
        List<Map<String, Object>> lowList = new ArrayList<>();
        for (Object[] r : safetySkus) {
            int safety = ((Number) r[3]).intValue();
            long total = totalBySku.getOrDefault(String.valueOf(r[0]), 0L);
            // 시스템 상태 기준과 일치: 0 < 총재고 ≤ 안전재고 → 부족 (총재고 0은 품절이므로 제외)
            if (total > 0 && total <= safety) {
                lowCount++;
                if (lowList.size() < 50) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", r[1]);
                    m.put("productName", r[2]);
                    m.put("safetyStock", safety);
                    m.put("totalQty", total);
                    lowList.add(m);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalSkuCount", totalSku);
        out.put("skusWithStockCount", skusWithStock);
        out.put("totalQty", totalQty);
        out.put("outOfStockCount", outOfStock);
        out.put("lowStockCount", lowCount);
        out.put("lowStock", lowList); // 최대 50개 표시(초과 시 lowStockCount 로 안내)
        return out;
    }

    /* ===== 유틸 ===== */

    private static String locLabel(Object complexName, Object locationLabel, Object storageLocationCode) {
        String label = nz(complexName);
        if (!nz(locationLabel).isBlank()) label = joinPath(label, nz(locationLabel));
        if (!nz(storageLocationCode).isBlank()) label = label + " (" + nz(storageLocationCode) + ")";
        return label;
    }

    private static String joinPath(String a, String b) {
        return a.isBlank() ? b : a + " › " + b;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String safeLower(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    private static String nz(Object o) { return o == null ? "" : o.toString(); }
}
