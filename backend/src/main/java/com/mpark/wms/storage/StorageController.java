package com.mpark.wms.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 이미지 업로드/삭제 REST — 프론트 storage.js 의 uploadImage/deleteImageByUrl 대응. */
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService service;

    @PostMapping("/upload")
    public UploadResult upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "prefix", defaultValue = "images") String prefix) {
        return service.upload(file, prefix);
    }

    @DeleteMapping
    public void delete(@RequestParam("url") String url) {
        service.deleteByUrl(url);
    }
}
