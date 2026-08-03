package com.mpark.wms.storage;

import com.mpark.wms.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 이미지 저장 — Supabase Storage(images 버킷) 대체.
 * 클라이언트가 압축한 파일을 디스크에 저장하고 /files/** 공개 URL 을 돌려준다.
 */
@Service
public class StorageService {

    private static final String MARKER = "/files/";

    private final Path baseDir;
    private final String publicBaseUrl;

    public StorageService(@Value("${app.storage.dir}") String dir,
                          @Value("${app.storage.public-base-url}") String publicBaseUrl) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    public UploadResult upload(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("파일이 비어 있습니다.");
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) throw ApiException.badRequest("이미지 파일만 업로드할 수 있습니다.");

        String safePrefix = sanitize(prefix == null || prefix.isBlank() ? "images" : prefix);
        String name = UUID.randomUUID().toString().replace("-", "") + "." + ext(ct);
        String relPath = safePrefix + "/" + name;

        try {
            Path target = baseDir.resolve(relPath).normalize();
            if (!target.startsWith(baseDir)) throw ApiException.badRequest("잘못된 경로입니다.");
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "업로드 실패: " + e.getMessage());
        }
        return new UploadResult(publicBaseUrl + "/" + relPath, relPath);
    }

    /** 임의 바이트 저장(예: 견적서 PDF). prefix 폴더에 uuid.ext 로 저장하고 공개 URL 을 반환. */
    public UploadResult uploadBytes(byte[] data, String prefix, String ext) {
        if (data == null || data.length == 0) throw ApiException.badRequest("파일이 비어 있습니다.");
        String safePrefix = sanitize(prefix == null || prefix.isBlank() ? "files" : prefix);
        String safeExt = (ext == null || ext.isBlank()) ? "bin" : ext.replaceAll("[^a-zA-Z0-9]", "");
        String name = UUID.randomUUID().toString().replace("-", "") + "." + safeExt;
        String relPath = safePrefix + "/" + name;
        try {
            Path target = baseDir.resolve(relPath).normalize();
            if (!target.startsWith(baseDir)) throw ApiException.badRequest("잘못된 경로입니다.");
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "업로드 실패: " + e.getMessage());
        }
        return new UploadResult(publicBaseUrl + "/" + relPath, relPath);
    }

    /** 공개 URL 로 객체 삭제. 실패해도 무시(Supabase deleteImageByUrl 동작과 동일) */
    public void deleteByUrl(String url) {
        if (url == null) return;
        int i = url.indexOf(MARKER);
        if (i < 0) return;
        String relPath = url.substring(i + MARKER.length());
        try {
            Path target = baseDir.resolve(relPath).normalize();
            if (target.startsWith(baseDir)) Files.deleteIfExists(target);
        } catch (Exception ignore) {
            // 무시
        }
    }

    private static String ext(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_\\-/]", "").replace("..", "");
    }
}
