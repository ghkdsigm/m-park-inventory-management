package com.mpark.wms.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 견적 리포지토리 (한 파일에 모음) */
interface QuoteRepository extends JpaRepository<Quote, String> {}

interface QuoteItemRepository extends JpaRepository<QuoteItem, String> {
    List<QuoteItem> findByQuoteIdOrderByLineNo(String quoteId);
    List<QuoteItem> findBySkuId(String skuId);
    List<QuoteItem> findBySkuIdIsNullOrderByCreatedAtDesc();
    void deleteByQuoteId(String quoteId);
}
