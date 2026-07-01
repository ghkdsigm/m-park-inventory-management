package com.mpark.wms.master;

import org.springframework.data.jpa.repository.JpaRepository;

/** 기준정보 4종 리포지토리 (한 파일에 모음) */
interface ComplexRepository extends JpaRepository<Complex, String> {}

interface CategoryRepository extends JpaRepository<Category, String> {}

interface ProductCodeRepository extends JpaRepository<ProductCode, String> {}

interface ProductDetailRepository extends JpaRepository<ProductDetail, String> {}
