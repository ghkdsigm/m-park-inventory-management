package com.mpark.wms.audit;

import com.mpark.wms.audit.AuditDtos.*;
import com.mpark.wms.common.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repo;
    private final CurrentUser currentUser;

    @PersistenceContext
    private EntityManager em;

    /** 감사로그 1건 기록 (스톡 RPC 의 log_audit 대체) */
    @Transactional
    public void log(String module, String action, String rowId, String label, String name) {
        log(module, action, rowId, label, name, null, null);
    }

    /** 변경 전/후 값 포함 기록 */
    @Transactional
    public void log(String module, String action, String rowId, String label, String name, String before, String after) {
        AuditLog a = new AuditLog();
        a.setModule(module);
        a.setTableName("skus");
        a.setAction(action);
        a.setRowId(rowId);
        a.setLabel(label);
        a.setName(name == null ? "" : name);
        a.setBeforeValue(trunc(before));
        a.setAfterValue(trunc(after));
        a.setIp(clientIp());
        a.setByUserId(currentUser.id());
        a.setByName(currentUser.name());
        repo.save(a);
    }

    private static String trunc(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private static String clientIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
                return req.getRemoteAddr();
            }
        } catch (Exception e) { /* 요청 밖(스케줄러 등)에서는 IP 없음 */ }
        return null;
    }

    /* ---------- 조회 (하루 단위 + module/byUserId 선택 필터) ---------- */
    @Transactional(readOnly = true)
    public List<AuditLog> list(String date, String module, String byUserId, int max) {
        StringBuilder q = new StringBuilder("select a from AuditLog a where 1=1");
        Map<String, Object> p = new HashMap<>();
        if (date != null && !date.isBlank()) {
            LocalDate d = LocalDate.parse(date);
            p.put("start", d.atStartOfDay());
            p.put("end", d.plusDays(1).atStartOfDay());
            q.append(" and a.at >= :start and a.at < :end");
        }
        if (module != null && !module.isBlank()) { q.append(" and a.module = :module"); p.put("module", module); }
        if (byUserId != null && !byUserId.isBlank()) { q.append(" and a.byUserId = :byUserId"); p.put("byUserId", byUserId); }
        q.append(" order by a.at desc");
        TypedQuery<AuditLog> query = em.createQuery(q.toString(), AuditLog.class);
        p.forEach(query::setParameter);
        return query.setMaxResults(max).getResultList();
    }

    @Transactional(readOnly = true)
    public List<TopUser> topUsers(int limit) {
        List<Object[]> rows = em.createQuery(
                "select a.byName, count(a) from AuditLog a where a.action = '생성' group by a.byName order by count(a) desc", Object[].class)
                .setMaxResults(limit).getResultList();
        List<TopUser> out = new ArrayList<>();
        for (Object[] r : rows) out.add(new TopUser(r[0] == null ? "(알수없음)" : (String) r[0], num(r[1])));
        return out;
    }

    @Transactional(readOnly = true)
    public List<TopProduct> topProductsBySku(int limit) {
        List<Object[]> rows = em.createQuery(
                "select s.productId, max(s.productName), count(s) from Sku s group by s.productId order by count(s) desc", Object[].class)
                .setMaxResults(limit).getResultList();
        List<TopProduct> out = new ArrayList<>();
        for (Object[] r : rows) out.add(new TopProduct((String) r[0], (String) r[1], num(r[2])));
        return out;
    }

    @Transactional(readOnly = true)
    public List<TopChanged> topChangedSkus(int limit) {
        // 상품명은 AuditLog 가 아니라 실제 Sku 테이블에서 직접 (항상 정확)
        List<Object[]> rows = em.createQuery(
                "select sk.id, sk.code, sk.productName, count(a) from AuditLog a, Sku sk " +
                "where a.tableName = 'skus' and a.rowId = sk.id " +
                "group by sk.id, sk.code, sk.productName order by count(a) desc", Object[].class)
                .setMaxResults(limit).getResultList();
        List<TopChanged> out = new ArrayList<>();
        for (Object[] r : rows) out.add(new TopChanged((String) r[0], (String) r[1], (String) r[2], num(r[3])));
        return out;
    }

    /* ---------- AI 사용 통계 ---------- */
    @Transactional(readOnly = true)
    public AuditDtos.AiStats aiStats(String dateFrom, String dateTo) {
        StringBuilder where = new StringBuilder("a.module = 'AI 어시스턴트'");
        Map<String, Object> p = new HashMap<>();
        if (dateFrom != null && !dateFrom.isBlank()) {
            where.append(" AND a.at >= :from");
            p.put("from", LocalDate.parse(dateFrom).atStartOfDay());
        }
        if (dateTo != null && !dateTo.isBlank()) {
            where.append(" AND a.at < :to");
            p.put("to", LocalDate.parse(dateTo).plusDays(1).atStartOfDay());
        }

        // 전체 집계
        long total = queryCount("SELECT COUNT(a) FROM AuditLog a WHERE " + where, p);
        long inbound = queryCount("SELECT COUNT(a) FROM AuditLog a WHERE " + where + " AND a.label LIKE '%AI 입고%'", p);
        long outbound = queryCount("SELECT COUNT(a) FROM AuditLog a WHERE " + where + " AND a.label LIKE '%AI 출고%'", p);

        // 사용자별
        List<Object[]> rows = em.createQuery(
                "SELECT a.byUserId, a.byName, COUNT(a), " +
                "SUM(CASE WHEN a.label LIKE '%AI 입고%' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN a.label LIKE '%AI 출고%' THEN 1 ELSE 0 END) " +
                "FROM AuditLog a WHERE " + where +
                " GROUP BY a.byUserId, a.byName ORDER BY COUNT(a) DESC", Object[].class)
                .setMaxResults(50).getResultList();
        // setParameter
        for (var e : p.entrySet()) {
            // re-run 을 위해 위 쿼리에 파라미터 적용은 아래에서
        }

        // 파라미터 바인딩 재수행 — 위 createQuery 에 직접 적용
        var q = em.createQuery(
                "SELECT a.byUserId, a.byName, COUNT(a), " +
                "SUM(CASE WHEN a.label LIKE '%AI 입고%' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN a.label LIKE '%AI 출고%' THEN 1 ELSE 0 END) " +
                "FROM AuditLog a WHERE " + where +
                " GROUP BY a.byUserId, a.byName ORDER BY COUNT(a) DESC", Object[].class);
        p.forEach(q::setParameter);
        rows = q.setMaxResults(50).getResultList();

        List<AuditDtos.AiUserStat> byUser = new ArrayList<>();
        for (Object[] r : rows) {
            byUser.add(new AuditDtos.AiUserStat(
                    (String) r[0], r[1] == null ? "(알수없음)" : (String) r[1],
                    num(r[2]), num(r[3]), num(r[4])));
        }
        return new AuditDtos.AiStats(total, inbound, outbound, byUser);
    }

    private long queryCount(String jpql, Map<String, Object> params) {
        var q = em.createQuery(jpql, Long.class);
        params.forEach(q::setParameter);
        Long r = q.getSingleResult();
        return r == null ? 0 : r;
    }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
}
