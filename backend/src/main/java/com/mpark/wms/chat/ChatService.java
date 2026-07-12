package com.mpark.wms.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mpark.wms.audit.AuditLog;
import com.mpark.wms.audit.AuditService;
import com.mpark.wms.common.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * AI 챗봇 서비스 — Claude API 호출 + 재고 컨텍스트 주입.
 * <p>DB 데이터(SKU·위치·재고)를 시스템 프롬프트에 포함해 Claude 에게 전달하고,
 * 사용자 확인 후 propose_action 도구를 호출하도록 유도한다.
 * 응답은 SSE 스트리밍으로 프론트엔드에 전달.</p>
 */
@Service
public class ChatService {

    @PersistenceContext
    private EntityManager em;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${app.ai.model:claude-sonnet-4-5-20241022}")
    private String model;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final AuditService auditService;
    private final CurrentUser currentUser;

    public ChatService(AuditService auditService, CurrentUser currentUser) {
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    /* ============================================================
     *  공개 API — 컨트롤러에서 호출
     * ============================================================ */

    /**
     * 트랜잭션 안에서 DB 컨텍스트를 로드한 뒤,
     * 스트리밍 본문(StreamingResponseBody)을 반환한다.
     * StreamingResponseBody 는 별도 스레드에서 실행되므로
     * DB 접근은 이 메서드 안에서 완료해야 한다.
     */
    @Transactional(readOnly = true)
    public StreamingResponseBody prepareChat(List<Map<String, Object>> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            return out -> writeEvent(out, Map.of("type", "error",
                    "text", "AI API 키가 설정되지 않았습니다. ANTHROPIC_API_KEY 환경변수를 확인하세요."));
        }

        // DB 데이터 로드 (트랜잭션 내)
        String systemPrompt = buildSystemPrompt();
        List<Map<String, Object>> claudeMessages = convertMessages(messages);

        // 감사로그: AI 대화 호출 기록 (유저 ID/이름은 요청 스레드에서 캡처)
        String userId = currentUser.id();
        String userName = currentUser.name();

        // 스트리밍 본문 — 별도 스레드에서 실행
        return out -> streamToApi(systemPrompt, claudeMessages, out, userId, userName);
    }

    /** AI 대화 감사로그 기록 (별도 트랜잭션 — 스트리밍 스레드에서 호출, SecurityContext 없음) */
    @Transactional
    public void logAiChat(String userId, String userName, String action, String label) {
        AuditLog a = new AuditLog();
        a.setModule("AI 어시스턴트");
        a.setTableName("chat");
        a.setAction(action);
        a.setLabel(label != null && label.length() > 255 ? label.substring(0, 255) : label);
        a.setByUserId(userId);
        a.setByName(userName != null ? userName : "");
        em.persist(a);
    }

    /* ============================================================
     *  Claude API 스트리밍 호출
     * ============================================================ */

    private void streamToApi(String systemPrompt, List<Map<String, Object>> messages, OutputStream out,
                             String userId, String userName) throws IOException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 2048);
            body.put("system", systemPrompt);
            body.put("messages", messages);
            body.put("tools", List.of(actionTool()));
            body.put("stream", true);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                String msg = "AI 서비스 오류 (" + resp.statusCode() + ")";
                try {
                    JsonNode errNode = mapper.readTree(err);
                    String detail = errNode.path("error").path("message").asText("");
                    if (!detail.isBlank()) msg = detail;
                } catch (Exception ignored) {}
                writeEvent(out, Map.of("type", "error", "text", msg));
                return;
            }

            // 스트림 파싱
            StringBuilder fullText = new StringBuilder();
            StringBuilder toolJson = new StringBuilder();
            String toolName = null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if (data.isEmpty()) continue;

                JsonNode node = mapper.readTree(data);
                String eventType = node.path("type").asText();

                switch (eventType) {
                    case "content_block_start" -> {
                        String blockType = node.path("content_block").path("type").asText();
                        if ("tool_use".equals(blockType)) {
                            toolName = node.path("content_block").path("name").asText();
                            toolJson.setLength(0);
                        }
                    }
                    case "content_block_delta" -> {
                        String deltaType = node.path("delta").path("type").asText();
                        if ("text_delta".equals(deltaType)) {
                            String text = node.path("delta").path("text").asText("");
                            if (!text.isEmpty()) {
                                fullText.append(text);
                                writeEvent(out, Map.of("type", "delta", "text", text));
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            toolJson.append(node.path("delta").path("partial_json").asText(""));
                        }
                    }
                    case "message_stop" -> { /* break handled below */ }
                    case "error" -> {
                        String errMsg = node.path("error").path("message").asText("알 수 없는 오류");
                        writeEvent(out, Map.of("type", "error", "text", errMsg));
                        return;
                    }
                    default -> { /* ping, message_start 등 무시 */ }
                }
            }

            // 완료 이벤트
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("type", "done");
            done.put("text", fullText.toString());
            String actionLabel = "AI 대화";
            if (toolJson.length() > 0 && "propose_action".equals(toolName)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> action = mapper.readValue(toolJson.toString(), Map.class);
                    done.put("action", action);
                    String aType = String.valueOf(action.getOrDefault("type", ""));
                    String aQty = String.valueOf(action.getOrDefault("qty", ""));
                    String aReason = String.valueOf(action.getOrDefault("reason", ""));
                    actionLabel = ("inbound".equals(aType) ? "AI 입고 제안" : "AI 출고 제안") + " · " + aQty + "개 · " + aReason;
                } catch (Exception ignored) {}
            }
            writeEvent(out, done);

            // 감사로그 기록
            try { logAiChat(userId, userName, "AI 대화", actionLabel); } catch (Exception ignored) {}

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeEvent(out, Map.of("type", "error", "text", "요청이 중단되었습니다."));
        } catch (Exception e) {
            writeEvent(out, Map.of("type", "error", "text", "오류: " + e.getMessage()));
        }
    }

    /* ============================================================
     *  시스템 프롬프트 빌드 (DB 컨텍스트 포함)
     * ============================================================ */

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 엠파크 WMS(재고관리시스템)의 AI 어시스턴트입니다.\n");
        sb.append("사용자가 텍스트, 사진, 음성으로 입고/출고를 요청하면 대화를 통해 정확한 정보를 수집하고 처리합니다.\n\n");

        // --- SKU ---
        @SuppressWarnings("unchecked")
        List<Object[]> skuRows = em.createQuery(
                "SELECT s.id, s.code, s.productName, s.spec, s.color, s.price, s.pathLabel FROM Sku s ORDER BY s.code",
                Object[].class
        ).setMaxResults(500).getResultList();

        sb.append("## 등록된 SKU 목록\n");
        if (skuRows.isEmpty()) {
            sb.append("(등록된 SKU가 없습니다. 사용자에게 SKU를 먼저 등록하라고 안내하세요.)\n");
        } else {
            sb.append("| ID | 코드 | 상품명 | 규격 | 색상 | 단가 | 분류경로 |\n");
            for (Object[] r : skuRows)
                sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s |\n",
                        r[0], r[1], r[2], nz(r[3]), nz(r[4]), r[5], nz(r[6])));
        }

        // --- 보관위치 ---
        @SuppressWarnings("unchecked")
        List<Object[]> locRows = em.createQuery(
                "SELECT l.id, l.code, l.name, l.complexName, l.zoneName, l.subZoneName, l.locationLabel FROM StorageLocation l ORDER BY l.code",
                Object[].class
        ).setMaxResults(200).getResultList();

        sb.append("\n## 보관위치 목록\n");
        if (locRows.isEmpty()) {
            sb.append("(등록된 보관위치가 없습니다.)\n");
        } else {
            sb.append("| ID | 코드 | 이름 | 단지 | 구역 | 상세구역 | 위치경로 |\n");
            for (Object[] r : locRows)
                sb.append(String.format("| %s | %s | %s | %s | %s | %s | %s |\n",
                        r[0], r[1], r[2], nz(r[3]), nz(r[4]), nz(r[5]), nz(r[6])));
        }

        // --- 현재 재고 ---
        @SuppressWarnings("unchecked")
        List<Object[]> stockRows = em.createQuery(
                "SELECT st.id, st.skuId, st.qty, st.complexName, st.locationLabel, st.storageLocationId FROM Stock st WHERE st.qty > 0 ORDER BY st.complexName",
                Object[].class
        ).setMaxResults(500).getResultList();

        sb.append("\n## 현재 재고 현황 (수량 > 0)\n");
        if (stockRows.isEmpty()) {
            sb.append("(현재 재고가 없습니다.)\n");
        } else {
            sb.append("| 재고행ID | SKU_ID | 수량 | 단지 | 위치경로 | 보관위치ID |\n");
            for (Object[] r : stockRows)
                sb.append(String.format("| %s | %s | %s개 | %s | %s | %s |\n",
                        r[0], r[1], r[2], nz(r[3]), nz(r[4]), r[5]));
        }

        // --- 규칙 ---
        sb.append("""

                ## 대화 규칙
                1. 항상 한국어로 친절하게 응답하세요.
                2. 사진이 첨부되면 제품을 식별하고 매칭되는 SKU를 찾아주세요.
                3. QR코드가 보이면 SKU 코드를 읽어서 매칭하세요.
                4. 입고에 필요한 정보: SKU, 보관위치, 수량, 사유
                   - 사유 선택지: 구매입고, 반품입고, 생산입고, 재고보충, 기타
                5. 출고에 필요한 정보: 재고행(SKU+위치), 수량, 사유
                   - 사유 선택지: 판매/사용, 폐기, 반품출고, 샘플/전시, 기타
                   - 선택 정보: 사용처, 요청부서, 요청자, 담당자
                6. 정보가 부족하면 사용자에게 질문하세요. 절대 임의로 추정하지 마세요.
                7. 모든 정보가 수집되면 요약을 보여주고 확인을 받으세요.
                8. 사용자가 '응', '네', '확인', '진행' 등으로 확인하면 propose_action 도구를 호출하세요.
                9. propose_action은 사용자가 최종 확인한 후에만 호출하세요. 정보 수집 중에는 절대 호출하지 마세요.
                10. 출고 시 현재 재고보다 많은 수량이면 경고하세요.
                11. 응답은 간결하고 명확하게 하세요.
                """);

        return sb.toString();
    }

    /* ============================================================
     *  도구 정의
     * ============================================================ */

    private Map<String, Object> actionTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string", "enum", List.of("inbound", "outbound"),
                "description", "입고(inbound) 또는 출고(outbound)"));
        props.put("skuId", Map.of("type", "string", "description", "SKU ID (입고 시 필수)"));
        props.put("storageLocationId", Map.of("type", "string", "description", "보관위치 ID (입고 시 필수)"));
        props.put("stockId", Map.of("type", "string", "description", "재고행 ID (출고 시 필수)"));
        props.put("qty", Map.of("type", "integer", "description", "수량"));
        props.put("reason", Map.of("type", "string", "description", "사유"));
        props.put("memo", Map.of("type", "string", "description", "메모 (선택)"));
        props.put("usagePlace", Map.of("type", "string", "description", "사용처 (출고 시 선택)"));
        props.put("requestDept", Map.of("type", "string", "description", "요청부서 (출고 시 선택)"));
        props.put("requester", Map.of("type", "string", "description", "요청자 (출고 시 선택)"));
        props.put("handler", Map.of("type", "string", "description", "담당자 (출고 시 선택)"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("type", "qty", "reason"));

        return Map.of(
                "name", "propose_action",
                "description", "입고 또는 출고 액션을 제안합니다. 사용자가 모든 정보를 확인한 후에만 호출하세요.",
                "input_schema", schema
        );
    }

    /* ============================================================
     *  메시지 변환 (프론트 → Claude API 형식)
     * ============================================================ */

    private List<Map<String, Object>> convertMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("role", msg.get("role"));

            List<Map<String, Object>> content = new ArrayList<>();

            // 이미지
            Object imgObj = msg.get("imageBase64");
            if (imgObj instanceof String imgData && !imgData.isBlank()) {
                String mediaType = "image/jpeg";
                String base64 = imgData;
                if (imgData.startsWith("data:")) {
                    int comma = imgData.indexOf(',');
                    if (comma > 0) {
                        String meta = imgData.substring(5, comma);
                        if (meta.contains(";")) mediaType = meta.split(";")[0];
                        base64 = imgData.substring(comma + 1);
                    }
                }
                content.add(Map.of("type", "image",
                        "source", Map.of("type", "base64", "media_type", mediaType, "data", base64)));
            }

            // 텍스트
            Object txtObj = msg.get("text");
            if (txtObj instanceof String text && !text.isBlank()) {
                content.add(Map.of("type", "text", "text", text));
            }

            if (!content.isEmpty()) {
                converted.put("content", content);
                result.add(converted);
            }
        }
        return result;
    }

    /* ============================================================
     *  유틸
     * ============================================================ */

    private void writeEvent(OutputStream out, Map<String, Object> data) throws IOException {
        out.write(("data: " + mapper.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String nz(Object o) { return o == null ? "" : o.toString(); }
}
