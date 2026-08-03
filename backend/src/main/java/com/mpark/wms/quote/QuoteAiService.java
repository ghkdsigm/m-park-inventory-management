package com.mpark.wms.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 견적서 텍스트 → 구조화(JSON) 추출. OpenAI(gpt-4o) function-calling 강제 호출.
 * 업체마다 양식이 달라 규칙 파서 대신 LLM 으로 파싱한다.
 */
@Service
public class QuoteAiService {

    @Value("${app.ai.api-key:}")
    private String apiKey;
    @Value("${app.ai.extract-model:gpt-4o}") // 견적 표 파싱 정확도 위해 고정확 모델
    private String model;
    @Value("${app.ai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final com.mpark.wms.usage.AiUsageService usageService;
    private final com.mpark.wms.common.security.CurrentUser currentUser;

    public QuoteAiService(com.mpark.wms.usage.AiUsageService usageService,
                          com.mpark.wms.common.security.CurrentUser currentUser) {
        this.usageService = usageService;
        this.currentUser = currentUser;
    }

    /**
     * 견적서 PDF 텍스트를 파싱해 헤더+품목을 담은 JsonNode 를 반환한다.
     * (vendorName, vendorBizNo, quoteDate, site, totalAmount, items[])
     */
    public JsonNode extract(String pdfText, String filenameHint) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("AI API 키가 설정되지 않았습니다. OPENAI_API_KEY 환경변수를 확인하세요.");

        String system = """
                당신은 견적서 파싱기입니다. 아래는 견적서 PDF에서 추출한 텍스트입니다(업체마다 양식이 다릅니다).
                extract_quote 도구로 다음을 정확히 추출하세요.
                - vendorName: 공급자(견적을 '보낸' 업체) 상호. 수신처(엠파크 등 '귀하'로 표기된 쪽)가 아닙니다.
                - vendorBizNo: 공급자(견적을 보낸 업체)의 사업자등록번호. 'XXX-XX-XXXXX'(숫자 3-2-5, 하이픈 포함) 형식입니다.
                  표 레이아웃이 깨져 있어도 '등록번호' 라벨 근처나 상단 공급자 영역에서 이 패턴의 번호를 반드시 찾아 넣으세요.
                  수신처(귀하/받는 쪽) 번호와 혼동하지 마세요.
                - quoteDate: 견적/작성일자를 YYYY-MM-DD 로. (예: '2026년 07월 04일'->2026-07-04, '2026.05.08'->2026-05-08)
                - site: 현장/단지. 파일명이나 본문의 '타워'/'허브' 등. 없으면 빈 문자열.
                - totalAmount: 합계금액(VAT포함 총액) 숫자만(콤마 없이).
                - items: 품목 각 줄.
                  name(품명), spec(규격), unit(단위), qty(수량 정수, 음수 정정줄은 부호 유지),
                  unitPrice(단가), amount(공급가액/금액), vat(세액/부가세액).
                규칙:
                - 표에는 '순번(NO/번호)' 칸과 '수량' 칸이 따로 있습니다. qty 에는 반드시 '수량' 칸 값만 넣고,
                  순번(1,2,3...)과 절대 혼동하지 마세요. 단가/금액/세액도 각 칸을 정확히 구분하세요.
                - 여러 페이지면 모든 페이지의 모든 품목 줄을 '하나도 빠짐없이' 추출하세요(페이지2 이후도 포함).
                - 검산(중요): 각 줄은 (수량 × 단가)가 공급가액/금액과 거의 같아야 합니다. 계산이 크게 어긋나면
                  수량·단가·금액 칸을 잘못 읽은 것이니, 그 줄의 숫자들을 다시 살펴 (수량 × 단가 = 금액)이 맞도록 바로잡아
                  출력하세요. (예: 단가 2,500·금액 125,000 이면 수량은 50입니다.)
                - '이하 여백', '====', '****', '소계', '합 계', '합계', '총 계', '총계' 같은 요약/빈 줄은 items 에서 제외.
                - 규격이 품명 칸에 섞여 있고 규격칸이 비어 있으면 규격으로 분리 시도(무리하면 그대로 두기).
                - 숫자에서 콤마/원화기호(\\,₩) 제거. 값이 없으면 0 또는 빈 문자열.
                """;

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text",
                "파일명 힌트: " + (filenameHint == null ? "" : filenameHint) + "\n\n[견적서 텍스트]\n" + pdfText));

        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "spec", Map.of("type", "string"),
                        "unit", Map.of("type", "string"),
                        "qty", Map.of("type", "integer"),
                        "unitPrice", Map.of("type", "number"),
                        "amount", Map.of("type", "number"),
                        "vat", Map.of("type", "number")),
                "required", List.of("name", "qty"));

        Map<String, Object> params = Map.of(
                "type", "object",
                "properties", Map.of(
                        "vendorName", Map.of("type", "string"),
                        "vendorBizNo", Map.of("type", "string"),
                        "quoteDate", Map.of("type", "string", "description", "YYYY-MM-DD"),
                        "site", Map.of("type", "string"),
                        "totalAmount", Map.of("type", "number"),
                        "items", Map.of("type", "array", "items", itemSchema)),
                "required", List.of("items"));

        Map<String, Object> tool = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "extract_quote",
                        "description", "견적서에서 헤더(업체·날짜·현장·합계)와 품목 목록을 추출한다.",
                        "parameters", params));

        return callToolOnce(system, content, tool, "extract_quote");
    }

    /** 지정 도구를 강제 호출하고 arguments(JSON)를 파싱해 반환. */
    private JsonNode callToolOnce(String system, List<Map<String, Object>> userContent,
                                  Map<String, Object> tool, String toolName) {
        String useModel = (model == null || model.isBlank()) ? "gpt-4o" : model.trim();
        String useBase = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.trim();
        useBase = useBase.replaceAll("/+$", "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("max_tokens", 8192);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", userContent)));
        body.put("tools", List.of(tool));
        body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", toolName)));

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(useBase + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200)
                throw new IllegalStateException("AI 서비스 오류 (" + resp.statusCode() + ")");

            JsonNode root = mapper.readTree(resp.body());
            try {
                JsonNode usage = root.path("usage");
                if (usage.isObject())
                    usageService.record(currentUser.id(), currentUser.name(), "quote_extract", useModel,
                            usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0),
                            usage.path("total_tokens").asInt(0), 0);
            } catch (Exception ignored) {}
            JsonNode args = root.path("choices").path(0).path("message")
                    .path("tool_calls").path(0).path("function").path("arguments");
            if (!args.isTextual())
                throw new IllegalStateException("견적서 파싱 결과가 비어 있습니다.");
            return mapper.readTree(args.asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청이 중단되었습니다.");
        } catch (IOException e) {
            throw new IllegalStateException("AI 서비스 호출 실패: " + e.getMessage());
        }
    }
}
