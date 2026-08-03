package com.mpark.wms.common;

import com.mpark.wms.stock.IdempotencyKeyRepository;
import com.mpark.wms.usage.AiUsageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 이력 테이블 정리(retention) — 무한 증가 방지.
 * - 멱등키: 재시도 대비용이라 3일만 보관.
 * - AI 사용량: 400일 보관(월별 추이 확인용).
 * (감사로그·입출고 원장은 업무 이력이라 자동 삭제하지 않음 — 필요 시 별도 정책으로 아카이브)
 */
@Service
@RequiredArgsConstructor
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final IdempotencyKeyRepository idemRepo;
    private final AiUsageRepository aiUsageRepo;

    /** 매일 새벽 3시 30분 실행. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanup() {
        try {
            int idem = idemRepo.deleteOlderThan(LocalDateTime.now().minusDays(3));
            int usage = aiUsageRepo.deleteOlderThan(LocalDateTime.now().minusDays(400));
            log.info("[cleanup] 멱등키 {}건, AI사용량 {}건 정리", idem, usage);
        } catch (Exception e) {
            log.warn("[cleanup] 실패: {}", e.getMessage());
        }
    }
}
