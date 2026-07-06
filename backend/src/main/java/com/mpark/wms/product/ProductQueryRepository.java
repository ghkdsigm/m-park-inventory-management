package com.mpark.wms.product;

import com.mpark.wms.product.ProductDtos.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 상품 동적 검색/페이징 — 상품관리 서버 페이징용. */
@Repository
@Transactional(readOnly = true)
public class ProductQueryRepository {

    @PersistenceContext
    private EntityManager em;

    public ProductPageResult managePage(ProductFilter f) {
        Map<String, Object> p = new HashMap<>();
        StringBuilder w = new StringBuilder(" from Product pr where 1=1 ");
        if (nb(f.categoryId()))      { w.append(" and pr.categoryId = :categoryId ");           p.put("categoryId", f.categoryId()); }
        if (nb(f.productCodeId()))   { w.append(" and pr.productCodeId = :productCodeId ");      p.put("productCodeId", f.productCodeId()); }
        if (nb(f.productDetailId())) { w.append(" and pr.productDetailId = :productDetailId ");  p.put("productDetailId", f.productDetailId()); }
        if (nb(f.search())) {
            w.append(" and (pr.name like :q or pr.maker like :q or pr.barcode like :q or pr.pathLabel like :q) ");
            p.put("q", "%" + f.search() + "%");
        }
        String where = w.toString();
        long total = bind(em.createQuery("select count(pr) " + where, Long.class), p).getSingleResult();
        int page = f.page() == null || f.page() < 1 ? 1 : f.page();
        int size = f.pageSize() == null || f.pageSize() < 1 ? 30 : f.pageSize();
        List<Product> rows = bind(em.createQuery("select pr " + where + " order by pr.createdAt desc", Product.class), p)
                .setFirstResult((page - 1) * size).setMaxResults(size).getResultList();
        return new ProductPageResult(rows, total);
    }

    private static boolean nb(String s) { return s != null && !s.isBlank(); }
    private static <T> TypedQuery<T> bind(TypedQuery<T> q, Map<String, Object> p) {
        p.forEach(q::setParameter);
        return q;
    }
}
