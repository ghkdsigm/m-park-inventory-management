package com.mpark.wms.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

/** AI 챗봇 — Claude API 스트리밍 프록시. 프론트에서 SSE로 수신. */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @SuppressWarnings("unchecked")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>) request.getOrDefault("messages", List.of());
        StreamingResponseBody body = chatService.prepareChat(messages);
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }
}
