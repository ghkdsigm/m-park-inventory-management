-- 검색/필터 성능 인덱스 — 자주 쓰는 동등 필터 컬럼(비FK). FK 컬럼(category_id 등)은 InnoDB 가 자동 인덱스 생성하므로 제외.
CREATE INDEX idx_skus_color        ON skus(color);
CREATE INDEX idx_skus_release_year ON skus(release_year);
CREATE INDEX idx_skus_product_name ON skus(product_name);
CREATE INDEX idx_products_name     ON products(name);
