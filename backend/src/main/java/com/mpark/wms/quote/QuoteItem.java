package com.mpark.wms.quote;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 견적 품목 — (견적서 x 줄). 견적의 예상 수량·단가의 원천.
 * SKU 와의 연결(skuId)은 업로드 시점엔 비어 있을 수 있고, 사람이 확인해 나중에 맺는다(지연 연결).
 * matchStatus: unmatched(미매칭) | suggested(AI추천, 미확정) | linked(확정 연결).
 */
@Getter
@Setter
@Entity
@Table(name = "quote_items")
public class QuoteItem extends BaseEntity {

    private String quoteId;

    private int lineNo = 0;
    private String rawName = "";         // 품명(원문)
    private String spec = "";            // 규격
    private String unit = "";            // 단위
    private int qty = 0;                 // 수량(정정줄은 음수 가능)
    private BigDecimal unitPrice = BigDecimal.ZERO;
    private BigDecimal amount = BigDecimal.ZERO;
    private BigDecimal vatAmount = BigDecimal.ZERO;

    private String skuId;                // 확정 연결된 SKU (nullable)
    private String suggestedSkuId;       // AI 추천 SKU (nullable)
    private String matchStatus = "unmatched";

    private String note = "";
}
