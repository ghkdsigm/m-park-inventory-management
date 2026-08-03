package com.mpark.wms.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.mpark.wms.audit.AuditService;
import com.mpark.wms.chat.ChatQueryService;
import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.security.CurrentUser;
import com.mpark.wms.quote.QuoteDtos.*;
import com.mpark.wms.storage.StorageService;
import com.mpark.wms.storage.UploadResult;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 견적서 업로드/추출/저장.
 * <p>업로드(upload): 파일 저장 + PDF 텍스트 추출 + gpt-4o 파싱 + SKU 자동추천 → 검토용 결과 반환(미저장).
 * 저장(create): 검토·수정된 내용을 Quote + QuoteItem 으로 영속.</p>
 * <p>upload 는 AI HTTP 호출(수 초)이 있어 트랜잭션으로 감싸지 않는다(DB 커넥션 장시간 점유 방지).
 * SKU 조회는 각기 독립 read tx 인 ChatQueryService 를 재활용한다.</p>
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteRepository quoteRepo;
    private final QuoteItemRepository itemRepo;
    private final QuoteAiService aiService;
    private final ChatQueryService chatQueryService; // SKU 유사 검색 재활용
    private final StorageService storageService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    /* ============ 업로드 → 추출 + 자동추천 (미저장) ============ */
    public QuoteUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("파일이 비어 있습니다.");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        boolean pdf = filename.toLowerCase().endsWith(".pdf") || "application/pdf".equals(file.getContentType());
        if (!pdf) throw ApiException.badRequest("PDF 파일만 업로드할 수 있습니다.");

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("파일을 읽을 수 없습니다.");
        }

        String text = extractText(bytes);
        if (text.isBlank())
            throw ApiException.badRequest("PDF에서 텍스트를 추출하지 못했습니다. (스캔 이미지 PDF는 현재 미지원)");

        JsonNode parsed = aiService.extract(text, filename);

        // 원본 파일 저장
        UploadResult stored = storageService.uploadBytes(bytes, "quotes", "pdf");

        // 헤더
        String vendorName = parsed.path("vendorName").asText("");
        String vendorBizNo = parsed.path("vendorBizNo").asText("");
        LocalDate quoteDate = parseDate(parsed.path("quoteDate").asText(""));
        String site = parsed.path("site").asText("");
        BigDecimal totalAmount = toDecimal(parsed.path("totalAmount"));

        // 품목 + SKU 자동추천
        List<ItemDto> items = new ArrayList<>();
        JsonNode arr = parsed.path("items");
        int line = 1;
        if (arr.isArray()) {
            for (JsonNode it : arr) {
                String name = it.path("name").asText("").trim();
                if (name.isBlank()) continue;
                String spec = it.path("spec").asText("");
                String unit = it.path("unit").asText("");
                int qty = it.path("qty").asInt(0);
                BigDecimal unitPrice = toDecimal(it.path("unitPrice"));
                BigDecimal amount = it.has("amount") && !it.path("amount").isNull()
                        ? toDecimal(it.path("amount"))
                        : unitPrice.multiply(BigDecimal.valueOf(qty));
                BigDecimal vat = toDecimal(it.path("vat"));

                String suggestedSkuId = null, suggestedLabel = null, matchStatus = "unmatched";
                Map<String, Object> hit = suggestSku(name);
                if (hit != null) {
                    suggestedSkuId = str(hit.get("skuId"));
                    suggestedLabel = (str(hit.get("code")) + " " + str(hit.get("productName"))).trim();
                    matchStatus = "suggested";
                }
                items.add(new ItemDto(line++, name, spec, unit, qty, unitPrice, amount, vat,
                        suggestedSkuId, suggestedLabel, matchStatus));
            }
        }

        return new QuoteUploadResult(vendorName, vendorBizNo, quoteDate, site, totalAmount,
                stored.url(), stored.path(), items);
    }

    /* ============ 저장(등록) ============ */
    @Transactional
    public Quote create(QuoteCreateRequest r) {
        if (r == null || r.items() == null || r.items().isEmpty())
            throw ApiException.badRequest("견적 품목이 없습니다.");

        Quote q = new Quote();
        q.setVendorName(nz(r.vendorName()));
        q.setVendorBizNo(nz(r.vendorBizNo()));
        q.setQuoteDate(r.quoteDate());
        q.setComplexId(blankToNull(r.complexId()));
        q.setComplexName(nz(r.complexName()));
        q.setSiteLabel(nz(r.siteLabel()));
        q.setTotalAmount(r.totalAmount() == null ? BigDecimal.ZERO : r.totalAmount());
        q.setItemCount(r.items().size());
        q.setFileUrl(nz(r.fileUrl()));
        q.setFilePath(nz(r.filePath()));
        q.setStatus("active");
        q.setUploadedBy(currentUser.id());
        q.setUploadedByName(nz(currentUser.name()));
        Quote saved = quoteRepo.save(q);

        int line = 1;
        for (ItemInput in : r.items()) {
            QuoteItem qi = new QuoteItem();
            qi.setQuoteId(saved.getId());
            qi.setLineNo(in.lineNo() > 0 ? in.lineNo() : line);
            qi.setRawName(nz(in.rawName()));
            qi.setSpec(nz(in.spec()));
            qi.setUnit(nz(in.unit()));
            qi.setQty(in.qty());
            qi.setUnitPrice(in.unitPrice() == null ? BigDecimal.ZERO : in.unitPrice());
            qi.setAmount(in.amount() == null ? BigDecimal.ZERO : in.amount());
            qi.setVatAmount(in.vatAmount() == null ? BigDecimal.ZERO : in.vatAmount());
            String skuId = blankToNull(in.skuId());
            qi.setSkuId(skuId);
            qi.setMatchStatus(skuId != null ? "linked" : "unmatched");
            qi.setNote(nz(in.note()));
            itemRepo.save(qi);
            line++;
        }

        auditService.log("견적", "견적서 등록", saved.getId(), saved.getVendorName(),
                saved.getVendorName(), null, "품목 " + saved.getItemCount() + "건");
        return saved;
    }

    /* ============ 조회 ============ */
    @Transactional(readOnly = true)
    public List<Quote> list() {
        return quoteRepo.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Transactional(readOnly = true)
    public QuoteDetail get(String id) {
        Quote q = quoteRepo.findById(id).orElseThrow(() -> ApiException.notFound("견적서를 찾을 수 없습니다."));
        List<DetailItem> items = new ArrayList<>();
        for (QuoteItem qi : itemRepo.findByQuoteIdOrderByLineNo(id)) {
            items.add(new DetailItem(qi.getId(), qi.getLineNo(), qi.getRawName(), qi.getSpec(), qi.getUnit(),
                    qi.getQty(), qi.getUnitPrice(), qi.getAmount(), qi.getVatAmount(),
                    qi.getSkuId(), qi.getSuggestedSkuId(), qi.getMatchStatus(), qi.getNote()));
        }
        return new QuoteDetail(q.getId(), q.getVendorName(), q.getVendorBizNo(), q.getQuoteDate(),
                q.getComplexId(), q.getComplexName(), q.getSiteLabel(), q.getTotalAmount(),
                q.getItemCount(), q.getFileUrl(), q.getStatus(), q.getCreatedAt(), items);
    }

    @Transactional
    public void remove(String id) {
        Quote q = quoteRepo.findById(id).orElseThrow(() -> ApiException.notFound("견적서를 찾을 수 없습니다."));
        itemRepo.deleteByQuoteId(id);
        quoteRepo.deleteById(id);
        if (q.getFileUrl() != null && !q.getFileUrl().isBlank()) storageService.deleteByUrl(q.getFileUrl());
        auditService.log("견적", "견적서 삭제", id, q.getVendorName(), q.getVendorName(), q.getVendorName(), null);
    }

    /* ============ 연동 (제품/입고 화면) ============ */

    /** 특정 SKU 에 연결된 견적 중 '가장 최근'(견적일 우선, 동률이면 생성시각) 1건 요약. 없으면 null. */
    @Transactional(readOnly = true)
    public QuoteForSku forSku(String skuId) {
        if (skuId == null || skuId.isBlank()) return null;
        List<QuoteItem> items = itemRepo.findBySkuId(skuId);
        if (items.isEmpty()) return null;

        List<String> qids = new ArrayList<>();
        for (QuoteItem it : items) if (!qids.contains(it.getQuoteId())) qids.add(it.getQuoteId());
        Map<String, Quote> qmap = new HashMap<>();
        for (Quote q : quoteRepo.findAllById(qids)) qmap.put(q.getId(), q);

        QuoteItem best = null;
        Quote bestQ = null;
        for (QuoteItem it : items) {
            Quote q = qmap.get(it.getQuoteId());
            if (q == null) continue;
            if (bestQ == null || newer(q, bestQ)) { best = it; bestQ = q; }
        }
        if (best == null) return null;
        return new QuoteForSku(best.getId(), bestQ.getId(), bestQ.getVendorName(), bestQ.getComplexName(),
                bestQ.getQuoteDate(), best.getUnit(), best.getQty(), best.getUnitPrice(), best.getAmount(), items.size());
    }

    /** SKU 미연결 견적 품목 목록(새 제품 등록 시 연결 후보). */
    @Transactional(readOnly = true)
    public List<UnmatchedItem> listUnmatched() {
        List<QuoteItem> items = itemRepo.findBySkuIdIsNullOrderByCreatedAtDesc();
        if (items.isEmpty()) return List.of();
        List<String> qids = new ArrayList<>();
        for (QuoteItem it : items) if (!qids.contains(it.getQuoteId())) qids.add(it.getQuoteId());
        Map<String, Quote> qmap = new HashMap<>();
        for (Quote q : quoteRepo.findAllById(qids)) qmap.put(q.getId(), q);

        List<UnmatchedItem> out = new ArrayList<>();
        for (QuoteItem it : items) {
            Quote q = qmap.get(it.getQuoteId());
            out.add(new UnmatchedItem(it.getId(), it.getQuoteId(),
                    q == null ? "" : q.getVendorName(), q == null ? null : q.getQuoteDate(),
                    it.getRawName(), it.getSpec(), it.getUnit(), it.getQty(), it.getUnitPrice()));
        }
        return out;
    }

    /** 견적 품목을 SKU 에 연결(또는 skuId 빈값이면 해제). 새 제품을 나중에 SKU 로 등록/입고할 때 사용. */
    @Transactional
    public void linkItem(String itemId, String skuId) {
        QuoteItem it = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("견적 품목을 찾을 수 없습니다."));
        String sid = blankToNull(skuId);
        it.setSkuId(sid);
        it.setMatchStatus(sid != null ? "linked" : "unmatched");
        itemRepo.save(it);
        auditService.log("견적", sid != null ? "견적품목 연결" : "견적품목 연결해제",
                it.getId(), it.getRawName(), it.getRawName(), null, "sku=" + nz(skuId));
    }

    /** a 견적이 b 견적보다 최신인가(견적일 우선, 동률/무일자면 생성시각). */
    private static boolean newer(Quote a, Quote b) {
        LocalDate da = a.getQuoteDate(), db = b.getQuoteDate();
        if (da != null && db != null && !da.isEqual(db)) return da.isAfter(db);
        if (da != null && db == null) return true;
        if (da == null && db != null) return false;
        return a.getCreatedAt() != null && b.getCreatedAt() != null && a.getCreatedAt().isAfter(b.getCreatedAt());
    }

    /* ============ 내부 ============ */

    /** 품명에서 뽑은 키워드로 기존 SKU 최상위 후보 1건을 찾는다(없으면 null). */
    private Map<String, Object> suggestSku(String name) {
        for (String kw : matchKeywords(name)) {
            List<Map<String, Object>> hits = chatQueryService.searchSku(kw, 1);
            if (hits != null && !hits.isEmpty()) return hits.get(0);
        }
        return null;
    }

    /** 품명에서 [브랜드]·(부가설명) 제거 후 2자 이상 토큰 목록(긴 것 우선). */
    private List<String> matchKeywords(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        String cleaned = name.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\([^)]*\\)", " ").trim();
        for (String p : cleaned.split("\\s+")) {
            String t = p.replaceAll("[^0-9A-Za-z가-힣]", "");
            if (t.length() >= 2 && t.matches(".*[가-힣A-Za-z].*")) out.add(t);
        }
        out.sort((a, b) -> Integer.compare(b.length(), a.length())); // 긴 토큰이 더 특이적
        return out;
    }

    private String extractText(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // 표 컬럼 정렬 유지(수량/단가 칸 어긋남 방지)
            String t = stripper.getText(doc);
            return t == null ? "" : t;
        } catch (IOException e) {
            throw ApiException.badRequest("PDF를 읽을 수 없습니다: " + e.getMessage());
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            return LocalDate.parse(t.substring(0, Math.min(10, t.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal toDecimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return BigDecimal.ZERO;
        try {
            if (n.isNumber()) return n.decimalValue();
            String s = n.asText("").replaceAll("[,\\s₩\\\\]", "");
            return s.isBlank() ? BigDecimal.ZERO : new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
}
