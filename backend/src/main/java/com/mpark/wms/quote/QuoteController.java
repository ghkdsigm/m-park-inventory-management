package com.mpark.wms.quote;

import com.mpark.wms.quote.QuoteDtos.LinkRequest;
import com.mpark.wms.quote.QuoteDtos.QuoteCreateRequest;
import com.mpark.wms.quote.QuoteDtos.QuoteDetail;
import com.mpark.wms.quote.QuoteDtos.QuoteForSku;
import com.mpark.wms.quote.QuoteDtos.QuoteUploadResult;
import com.mpark.wms.quote.QuoteDtos.UnmatchedItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 견적서 API.
 * - POST /api/quotes/upload : PDF 업로드 → 추출+SKU추천 (미저장, 검토용)
 * - POST /api/quotes        : 검토 결과 저장
 * - GET  /api/quotes        : 목록
 * - GET  /api/quotes/{id}    : 상세(품목 포함)
 * - DELETE /api/quotes/{id}  : 삭제
 * (쓰기는 SecurityConfig 기본 규칙에 따라 관리자 전용)
 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService service;

    @PostMapping("/upload")
    public QuoteUploadResult upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    @PostMapping
    public Quote create(@RequestBody QuoteCreateRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<Quote> list() {
        return service.list();
    }

    /** 서버 페이징 목록 + 필터(업체/단지/월/검색). */
    @GetMapping("/page")
    public java.util.Map<String, Object> page(@RequestParam(required = false) String vendor,
                                              @RequestParam(required = false) String complex,
                                              @RequestParam(required = false) String month,
                                              @RequestParam(required = false) String search,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return service.listPaged(vendor, complex, month, search, page, pageSize);
    }

    /** 필터 드롭다운용 distinct 값(업체/단지/월). */
    @GetMapping("/filter-options")
    public java.util.Map<String, Object> filterOptions() {
        return service.filterOptions();
    }

    @GetMapping("/{id}")
    public QuoteDetail get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable String id) {
        service.remove(id);
    }

    /** 특정 SKU 의 최근 연결 견적 요약 (제품 단가 placeholder·입고 견적수량/오차용). 없으면 null. */
    @GetMapping("/for-sku/{skuId}")
    public QuoteForSku forSku(@PathVariable String skuId) {
        return service.forSku(skuId);
    }

    /** SKU 미연결 견적 품목 목록 (새 제품 등록 시 연결 후보). */
    @GetMapping("/unmatched")
    public List<UnmatchedItem> unmatched() {
        return service.listUnmatched();
    }

    /** 견적 품목을 SKU 에 연결(또는 skuId 빈값이면 해제). */
    @PostMapping("/items/{itemId}/link")
    public void link(@PathVariable String itemId, @RequestBody LinkRequest req) {
        service.linkItem(itemId, req == null ? null : req.skuId());
    }
}
