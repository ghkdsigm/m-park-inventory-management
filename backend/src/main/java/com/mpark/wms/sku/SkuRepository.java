package com.mpark.wms.sku;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SkuRepository extends JpaRepository<Sku, String> {

    List<Sku> findAllByOrderByCreatedAtDesc();

    List<Sku> findByProductIdOrderByCodeAsc(String productId);

    List<Sku> findByIdIn(Collection<String> ids);

    Optional<Sku> findByCode(String code);

    /** 재고 작업 시 SKU 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sku s where s.id = :id")
    Optional<Sku> findByIdForUpdate(@Param("id") String id);
}
