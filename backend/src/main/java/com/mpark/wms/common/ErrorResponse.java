package com.mpark.wms.common;

/**
 * 공통 에러 응답 바디. 프론트(db.js)는 이 message 를 읽어 throw 한다.
 */
public record ErrorResponse(String message) {
}
