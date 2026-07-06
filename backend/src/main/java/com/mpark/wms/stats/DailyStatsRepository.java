package com.mpark.wms.stats;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailyStatsRepository extends JpaRepository<DailyStats, LocalDate> {
    List<DailyStats> findAllByOrderByStatDateDesc(Pageable pageable);
    List<DailyStats> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);
}
