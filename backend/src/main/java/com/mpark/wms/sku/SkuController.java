package com.mpark.wms.sku;

import com.mpark.wms.sku.SkuDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SKU REST — 프론트 db.js 의 skus 보드와 매핑.
 * (setLocation / verifyLocation 은 Phase 4 에서 추가)
 */
@RestController
@RequestMapping("/api/skus")
@RequiredArgsConstructor
public class SkuController {

    private final SkuService service;

    // 리터럴 경로가 {id} 보다 우선 매칭됨 (Spring PathPattern)
    @GetMapping
    public List<Sku> list() { return service.list(); }

    @GetMapping("/lifecycle")
    public List<StockRow> listLifecycle() { return service.listLifecycle(); }

    @GetMapping("/filter-options")
    public FilterOptions filterOptions() { return service.filterOptions(); }

    @GetMapping("/by-code/{code}")
    public Sku getByCode(@PathVariable String code) { return service.getByCode(code); }

    @GetMapping("/by-product/{productId}")
    public List<Sku> listByProduct(@PathVariable String productId) { return service.listByProduct(productId); }

    @PostMapping("/page")
    public SkuPageResult page(@RequestBody SkuFilter filter) { return service.page(filter); }

    @PostMapping("/manage-page")
    public SkuListPageResult managePage(@RequestBody SkuFilter filter) { return service.managePage(filter); }

    @PostMapping("/page-by-sku")
    public SkuAggPageResult pageBySku(@RequestBody SkuFilter filter) { return service.pageBySku(filter); }

    @PostMapping("/group-by-complex")
    public List<ComplexGroupRow> groupByComplex(@RequestBody SkuFilter filter) { return service.groupByComplex(filter); }

    @PostMapping("/by-ids")
    public List<Sku> listByIds(@RequestBody List<String> ids) { return service.listByIds(ids); }

    @GetMapping("/{id}")
    public Sku get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public Sku create(@RequestBody SkuRequest r) { return service.create(r); }

    @PutMapping("/{id}")
    public Sku update(@PathVariable String id, @RequestBody SkuRequest r) { return service.update(id, r); }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable String id) { service.remove(id); }
}
