package com.mpark.wms.master;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 기준정보 4단계 REST. 프론트 db.js 의 complexes/categories/productCodes/productDetails 보드와 1:1.
 *   GET    /api/{board}        목록
 *   GET    /api/{board}/{id}   단건
 *   POST   /api/{board}        생성
 *   PUT    /api/{board}/{id}   수정
 *   DELETE /api/{board}/{id}   삭제
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MasterController {

    private final ComplexService complexService;
    private final CategoryService categoryService;
    private final ProductCodeService productCodeService;
    private final ProductDetailService productDetailService;

    /* ---------- 단지 ---------- */
    @GetMapping("/complexes")
    public List<Complex> listComplexes() { return complexService.list(); }

    @GetMapping("/complexes/{id}")
    public Complex getComplex(@PathVariable String id) { return complexService.get(id); }

    @PostMapping("/complexes")
    public Complex createComplex(@RequestBody ComplexRequest r) { return complexService.create(r); }

    @PutMapping("/complexes/{id}")
    public Complex updateComplex(@PathVariable String id, @RequestBody ComplexRequest r) { return complexService.update(id, r); }

    @DeleteMapping("/complexes/{id}")
    public void removeComplex(@PathVariable String id) { complexService.remove(id); }

    /* ---------- 카테고리 ---------- */
    @GetMapping("/categories")
    public List<Category> listCategories() { return categoryService.list(); }

    @GetMapping("/categories/{id}")
    public Category getCategory(@PathVariable String id) { return categoryService.get(id); }

    @PostMapping("/categories")
    public Category createCategory(@RequestBody CategoryRequest r) { return categoryService.create(r); }

    @PutMapping("/categories/{id}")
    public Category updateCategory(@PathVariable String id, @RequestBody CategoryRequest r) { return categoryService.update(id, r); }

    @DeleteMapping("/categories/{id}")
    public void removeCategory(@PathVariable String id) { categoryService.remove(id); }

    /* ---------- 제품코드 ---------- */
    @GetMapping("/product-codes")
    public List<ProductCode> listProductCodes() { return productCodeService.list(); }

    @GetMapping("/product-codes/{id}")
    public ProductCode getProductCode(@PathVariable String id) { return productCodeService.get(id); }

    @PostMapping("/product-codes")
    public ProductCode createProductCode(@RequestBody ProductCodeRequest r) { return productCodeService.create(r); }

    @PutMapping("/product-codes/{id}")
    public ProductCode updateProductCode(@PathVariable String id, @RequestBody ProductCodeRequest r) { return productCodeService.update(id, r); }

    @DeleteMapping("/product-codes/{id}")
    public void removeProductCode(@PathVariable String id) { productCodeService.remove(id); }

    /* ---------- 제품상세코드 ---------- */
    @GetMapping("/product-details")
    public List<ProductDetail> listProductDetails() { return productDetailService.list(); }

    @GetMapping("/product-details/{id}")
    public ProductDetail getProductDetail(@PathVariable String id) { return productDetailService.get(id); }

    @PostMapping("/product-details")
    public ProductDetail createProductDetail(@RequestBody ProductDetailRequest r) { return productDetailService.create(r); }

    @PutMapping("/product-details/{id}")
    public ProductDetail updateProductDetail(@PathVariable String id, @RequestBody ProductDetailRequest r) { return productDetailService.update(id, r); }

    @DeleteMapping("/product-details/{id}")
    public void removeProductDetail(@PathVariable String id) { productDetailService.remove(id); }
}
