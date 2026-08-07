package com.mpark.wms.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    /** 특정 대상 행들(rowId)에 대한 module/action 접두 로그를 최신순으로. (견적 SKU 연결 로그 등) */
    List<AuditLog> findByModuleAndActionStartingWithAndRowIdInOrderByAtDesc(
            String module, String actionPrefix, Collection<String> rowIds);
}
