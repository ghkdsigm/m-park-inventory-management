package com.mpark.wms.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, String> {

    List<Stock> findBySkuId(String skuId);

    boolean existsBySkuId(String skuId);

    List<Stock> findBySkuIdIn(java.util.Collection<String> skuIds);

    Optional<Stock> findBySkuIdAndStorageLocationId(String skuId, String storageLocationId);

    List<Stock> findByComplexId(String complexId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.id = :id")
    Optional<Stock> findByIdForUpdate(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.skuId = :skuId and s.storageLocationId = :locId")
    Optional<Stock> findBySkuAndLocationForUpdate(@Param("skuId") String skuId, @Param("locId") String locId);
}
