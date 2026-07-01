package com.mpark.wms.master;

/**
 * 기준정보 생성/수정 요청 DTO.
 * 프론트(MasterCodeBoard.vue)가 비정규화 이름/경로(complexName, pathLabel 등)를 만들어 보내므로 그대로 받는다.
 * 자동코드형(category/productCode/productDetail)은 code 를 보내지 않음 → 서버 생성.
 */
record ComplexRequest(String code, String name, String description) {}

record CategoryRequest(String name, String description, String pathLabel) {}

record ProductCodeRequest(String name, String description,
                          String categoryId, String categoryName, String pathLabel) {}

record ProductDetailRequest(String name, String description,
                            String productCodeId, String productCodeName,
                            String categoryId, String categoryName, String pathLabel) {}
