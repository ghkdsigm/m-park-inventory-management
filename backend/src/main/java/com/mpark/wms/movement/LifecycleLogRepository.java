package com.mpark.wms.movement;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LifecycleLogRepository extends JpaRepository<LifecycleLog, String> {
    List<LifecycleLog> findBySkuIdOrderByAtDesc(String skuId, Pageable pageable);
}
