package com.mpark.wms.storage;

/** 업로드 결과 — 프론트 storage.js 가 기대하는 {url, path}. */
public record UploadResult(String url, String path) {
}
