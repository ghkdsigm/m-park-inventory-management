package com.mpark.wms.product;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 상품 REST — 프론트 db.js 의 products 보드와 매핑. */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping
    public List<Product> list() { return service.list(); }

    @GetMapping("/{id}")
    public Product get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public Product create(@RequestBody ProductRequest r) { return service.create(r); }

    @PutMapping("/{id}")
    public Product update(@PathVariable String id, @RequestBody ProductRequest r) { return service.update(id, r); }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable String id) { service.remove(id); }
}
