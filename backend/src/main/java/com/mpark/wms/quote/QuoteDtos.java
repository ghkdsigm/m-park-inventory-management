package com.mpark.wms.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 견적 관련 요청/응답 DTO (record 모음). */
public final class QuoteDtos {
    private QuoteDtos() {}

    /** 업로드 추출 결과(아직 미저장) — 검토 화면용. */
    public record QuoteUploadResult(
            String vendorName, String vendorBizNo, LocalDate quoteDate,
            String siteLabel, BigDecimal totalAmount,
            String fileUrl, String filePath, List<ItemDto> items) {}

    public record ItemDto(
            int lineNo, String rawName, String spec, String unit,
            int qty, BigDecimal unitPrice, BigDecimal amount, BigDecimal vatAmount,
            String suggestedSkuId, String suggestedSkuLabel, String matchStatus) {}

    /** 저장(등록) 요청 — 검토 화면에서 확인/수정한 결과. */
    public record QuoteCreateRequest(
            String vendorName, String vendorBizNo, LocalDate quoteDate,
            String complexId, String complexName, String siteLabel,
            BigDecimal totalAmount, String fileUrl, String filePath,
            List<ItemInput> items) {}

    public record ItemInput(
            int lineNo, String rawName, String spec, String unit,
            int qty, BigDecimal unitPrice, BigDecimal amount, BigDecimal vatAmount,
            String skuId, String note) {}

    /** 상세 조회 결과. */
    public record QuoteDetail(
            String id, String vendorName, String vendorBizNo, LocalDate quoteDate,
            String complexId, String complexName, String siteLabel,
            BigDecimal totalAmount, int itemCount, String fileUrl, String status,
            LocalDateTime createdAt, List<DetailItem> items) {}

    public record DetailItem(
            String id, int lineNo, String rawName, String spec, String unit,
            int qty, BigDecimal unitPrice, BigDecimal amount, BigDecimal vatAmount,
            String skuId, String suggestedSkuId, String matchStatus, String note) {}

    /** 특정 SKU 의 '최근' 연결 견적 요약 — 제품/입고 화면 연동용(단가 placeholder·견적수량·오차). */
    public record QuoteForSku(
            String quoteItemId, String quoteId, String vendorName, String complexName,
            LocalDate quoteDate, String unit, int qty, BigDecimal unitPrice, BigDecimal amount, int linkedCount) {}

    /** SKU 미연결 견적 품목 — 새 제품 등록 시 연결 후보 목록. */
    public record UnmatchedItem(
            String quoteItemId, String quoteId, String vendorName, LocalDate quoteDate,
            String rawName, String spec, String unit, int qty, BigDecimal unitPrice) {}

    /** 견적 품목 ↔ SKU 연결/해제 요청. */
    public record LinkRequest(String skuId) {}

    /** SKU 연결 로그 한 줄 — 누가/언제 어떤 견적품목을 어떤 SKU에 연결(또는 해제)했는지 (감사로그 기반). */
    public record LinkLog(LocalDateTime at, String byName, String action, String itemName, String skuLabel) {}
}
