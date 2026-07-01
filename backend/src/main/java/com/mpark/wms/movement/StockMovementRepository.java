package com.mpark.wms.movement;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {

    List<StockMovement> findBySkuIdOrderByAtDesc(String skuId, Pageable pageable);

    List<StockMovement> findAllByOrderByAtDesc(Pageable pageable);

    /** 취소 처리 시 원거래 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from StockMovement m where m.id = :id")
    Optional<StockMovement> findByIdForUpdate(@Param("id") String id);
}
