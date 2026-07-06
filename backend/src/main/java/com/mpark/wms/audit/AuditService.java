package com.mpark.wms.audit;

import com.mpark.wms.audit.AuditDtos.*;
import com.mpark.wms.common.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        AuditLog a = new AuditLog();
        a.setModule(module);
        a.setTableName("skus");
        a.setAction(action);
        a.setRowId(rowId);
        a.setLabel(label);
        a.setName(name == null ? "" : name);
        a.setByUserId(currentUser.id());
        a.setByName(currentUser.name());
        repo.save(a);
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

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
}
