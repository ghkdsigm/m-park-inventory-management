package com.mpark.wms.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 업무 예외 — Supabase RPC 의 `raise exception '...'` 를 대체.
 * 메시지는 프론트 토스트에 그대로 노출되므로 한글 메시지를 그대로 사용한다.
 */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }
}
