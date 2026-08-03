package com.mpark.wms.usage;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI 토큰/문자 사용량 1건 — 계정별·기능별 비용 모니터링용.
 * feature: chat | find_similar | quote_extract | tts
 */
@Getter
@Setter
@Entity
@Table(name = "ai_usage")
public class AiUsage {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id;

    private String userId;
    private String userName = "";
    private String feature;
    private String model = "";
    private int promptTokens = 0;
    private int completionTokens = 0;
    private int totalTokens = 0;
    private int charCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void pre() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
