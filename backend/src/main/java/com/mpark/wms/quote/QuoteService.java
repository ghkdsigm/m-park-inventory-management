package com.mpark.wms.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.mpark.wms.audit.AuditLog;
import com.mpark.wms.audit.AuditLogRepository;
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
    private final AuditLogRepository auditLogRepo;
    private final CurrentUser currentUser;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /* ============ 업로드 → 추출 + 자동추천 (미저장) ============ */
    public QuoteUploadResult upload(MultipartFile file, String complexId, String complexName) {
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

        // 월 1회 제한: 같은 (단지·업체·월) 견적서가 이미 있으면 업로드 차단
        if (existsForMonth(blankToNull(complexId), vendorName, quoteDate)) {
            String mm = quoteDate != null ? quoteDate.getYear() + "년 " + quoteDate.getMonthValue() + "월 " : "";
            throw ApiException.badRequest("이미 " + mm + "'" + vendorName + "' 업체의 " + nz(complexName)
                    + " 견적서가 등록되어 있습니다. (단지·업체·월 1회만 업로드 가능)");
        }

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
                Map<String, Object> hit = suggestSku(name, site);
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
        // 월 1회 제한(최종 가드)
        if (existsForMonth(blankToNull(r.complexId()), nz(r.vendorName()), r.quoteDate()))
            throw ApiException.badRequest("이미 해당 월에 '" + nz(r.vendorName()) + "' 업체의 "
                    + nz(r.complexName()) + " 견적서가 등록되어 있습니다. (단지·업체·월 1회)");

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

    /** 견적 목록 서버 페이징 + 필터(업체/단지/월/검색). */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> listPaged(String vendor, String complexName, String yearMonth,
                                                   String search, int page, int pageSize) {
        StringBuilder w = new StringBuilder(" where 1=1");
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        if (nb(vendor)) { w.append(" and q.vendorName = :vendor"); p.put("vendor", vendor); }
        if (nb(complexName)) { w.append(" and q.complexName = :cx"); p.put("cx", complexName); }
        if (nb(yearMonth)) {
            try {
                LocalDate ms = LocalDate.parse(yearMonth + "-01");
                w.append(" and q.quoteDate >= :ms and q.quoteDate < :me");
                p.put("ms", ms); p.put("me", ms.plusMonths(1));
            } catch (Exception ignored) {}
        }
        if (nb(search)) {
            w.append(" and (lower(q.vendorName) like :s or lower(q.vendorBizNo) like :s)");
            p.put("s", "%" + search.toLowerCase() + "%");
        }
        var countQ = em.createQuery("select count(q) from Quote q" + w, Long.class);
        p.forEach(countQ::setParameter);
        long total = countQ.getSingleResult();

        var rowQ = em.createQuery("select q from Quote q" + w + " order by q.createdAt desc", Quote.class);
        p.forEach(rowQ::setParameter);
        int size = Math.max(1, Math.min(pageSize, 100));
        List<Quote> rows = rowQ.setFirstResult(Math.max(0, page - 1) * size).setMaxResults(size).getResultList();

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }

    /** 필터 드롭다운용 distinct 값(업체/단지/월). */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> filterOptions() {
        List<String> vendors = em.createQuery(
                "select distinct q.vendorName from Quote q where q.vendorName <> '' order by q.vendorName", String.class).getResultList();
        List<String> complexes = em.createQuery(
                "select distinct q.complexName from Quote q where q.complexName <> '' order by q.complexName", String.class).getResultList();
        List<LocalDate> dates = em.createQuery(
                "select distinct q.quoteDate from Quote q where q.quoteDate is not null order by q.quoteDate desc", LocalDate.class).getResultList();
        java.util.List<String> months = new java.util.ArrayList<>();
        for (LocalDate d : dates) {
            String ym = String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            if (!months.contains(ym)) months.add(ym);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("vendors", vendors);
        out.put("complexes", complexes);
        out.put("months", months);
        return out;
    }

    private static boolean nb(String s) { return s != null && !s.isBlank(); }

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

    /** 견적의 SKU 연결/해제 로그 — 누가/언제/어떤 품목을 어떤 SKU에 (감사로그 기반, 최신순). */
    @Transactional(readOnly = true)
    public List<LinkLog> linkLogs(String quoteId) {
        List<QuoteItem> items = itemRepo.findByQuoteIdOrderByLineNo(quoteId);
        if (items.isEmpty()) return List.of();
        List<String> ids = items.stream().map(QuoteItem::getId).toList();
        List<AuditLog> logs = auditLogRepo.findByModuleAndActionStartingWithAndRowIdInOrderByAtDesc("견적", "견적품목 연결", ids);
        List<LinkLog> out = new ArrayList<>();
        for (AuditLog l : logs) {
            String skuId = extractSkuId(l.getAfterValue());
            String skuLabel = skuId == null ? "(연결 해제)" : skuLabel(skuId);
            out.add(new LinkLog(l.getAt(), l.getByName(), l.getAction(), l.getName(), skuLabel));
        }
        return out;
    }

    /** afterValue "sku=xxx" 에서 skuId 추출. 빈 값(해제)이면 null. */
    private static String extractSkuId(String afterValue) {
        if (afterValue == null) return null;
        int i = afterValue.indexOf("sku=");
        if (i < 0) return null;
        String v = afterValue.substring(i + 4).trim();
        return v.isEmpty() ? null : v;
    }

    /** skuId → "품명 규격 (코드)" 표시 라벨. 조회 실패 시 id 그대로. */
    private String skuLabel(String skuId) {
        try {
            Object[] r = (Object[]) em.createNativeQuery(
                    "SELECT product_name, spec, code FROM skus WHERE id = ?1").setParameter(1, skuId).getSingleResult();
            String pn = r[0] == null ? "" : r[0].toString();
            String sp = r[1] == null ? "" : r[1].toString();
            String code = r[2] == null ? "" : r[2].toString();
            String label = (pn + (sp.isBlank() ? "" : " " + sp)).trim();
            return code.isBlank() ? label : (label.isBlank() ? code : label + " (" + code + ")");
        } catch (Exception e) {
            return skuId;
        }
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

    /**
     * 품명 키워드로 기존 SKU 후보를 찾는다. 견적 현장(site)이 있으면 그 단지 SKU를 우선 추천
     * (같은 품목·규격이 단지별 SKU로 나뉘므로). 단지 일치 후보가 없으면 최상위 후보.
     */
    private Map<String, Object> suggestSku(String name, String site) {
        String s = site == null ? "" : site.replaceAll("\\s", "");
        for (String kw : matchKeywords(name)) {
            List<Map<String, Object>> hits = chatQueryService.searchSku(kw, 5);
            if (hits != null && !hits.isEmpty()) {
                if (!s.isBlank()) {
                    for (Map<String, Object> h : hits) {
                        String hc = str(h.get("complexName")).replaceAll("\\s", "");
                        if (!hc.isBlank() && s.contains(hc)) return h; // 현장에 단지명 포함 → 그 단지 SKU
                    }
                }
                return hits.get(0);
            }
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

    /** 같은 (단지·업체·월)에 활성 견적서가 이미 있는가. (월 1회 업로드 제한) */
    boolean existsForMonth(String complexId, String vendorName, LocalDate date) {
        if (complexId == null || vendorName == null || vendorName.isBlank() || date == null) return false;
        LocalDate ms = date.withDayOfMonth(1);
        Long n = em.createQuery(
                "select count(q) from Quote q where q.complexId = :cx and q.vendorName = :v "
                        + "and q.status = 'active' and q.quoteDate >= :ms and q.quoteDate < :me", Long.class)
                .setParameter("cx", complexId).setParameter("v", vendorName)
                .setParameter("ms", ms).setParameter("me", ms.plusMonths(1)).getSingleResult();
        return n != null && n > 0;
    }
}
