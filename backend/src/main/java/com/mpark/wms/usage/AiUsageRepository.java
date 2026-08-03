package com.mpark.wms.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AiUsageRepository extends JpaRepository<AiUsage, String> {

    /** 오래된 사용량 기록 정리(retention). */
    @Modifying
    @Query("delete from AiUsage u where u.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT u.userId, u.userName, SUM(u.promptTokens), SUM(u.completionTokens), " +
           "SUM(u.totalTokens), SUM(u.charCount), COUNT(u) " +
           "FROM AiUsage u WHERE u.createdAt >= :from GROUP BY u.userId, u.userName " +
           "ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> summaryByUser(@Param("from") LocalDateTime from);

    @Query("SELECT u.feature, SUM(u.totalTokens), SUM(u.charCount), COUNT(u), " +
           "SUM(u.promptTokens), SUM(u.completionTokens) " +
           "FROM AiUsage u WHERE u.createdAt >= :from GROUP BY u.feature " +
           "ORDER BY SUM(u.totalTokens) DESC")
    List<Object[]> summaryByFeature(@Param("from") LocalDateTime from);
}
