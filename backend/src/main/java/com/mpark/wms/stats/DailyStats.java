package com.mpark.wms.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 일별 집계 (date PK). */
@Getter
@Setter
@Entity
@Table(name = "daily_stats")
public class DailyStats {

    @Id
    @Column(name = "stat_date")
    private LocalDate statDate;

    private int moveCount;
    private int inCount;
    private int outCount;
    private int adjustCount;
    private int auditCount;
    private int inQty;
    private int outQty;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
