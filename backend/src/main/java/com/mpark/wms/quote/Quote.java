package com.mpark.wms.quote;

import com.mpark.wms.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 견적서 — 업체가 보낸 견적 1건(헤더). 품목은 QuoteItem 이 (견적서 x 줄)로 관리.
 * 원본 PDF 는 /files 에 저장하고 file_url 로 참조. 현장(단지)은 denormalized complexId+complexName.
 */
@Getter
@Setter
@Entity
@Table(name = "quotes")
public class Quote extends BaseEntity {

    private String vendorName = "";      // 공급자(견적을 보낸 업체) 상호
    private String vendorBizNo = "";     // 공급자 사업자등록번호
    private LocalDate quoteDate;         // 견적/작성일자

    private String complexId;            // 현장(단지) FK — nullable
    private String complexName = "";
    private String siteLabel = "";       // 견적서/파일명에서 읽은 현장 원문(타워/허브 등)

    private BigDecimal totalAmount = BigDecimal.ZERO; // 합계(VAT포함)
    private int itemCount = 0;

    private String fileUrl = "";         // 원본 PDF 공개 URL
    private String filePath = "";        // 저장 상대경로

    private String status = "active";    // active | canceled

    private String uploadedBy;           // 업로드 사용자 id
    private String uploadedByName = "";
}
