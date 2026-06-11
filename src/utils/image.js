/**
 * 이미지 표시 우선순위 공통 유틸 (화면마다 중복 구현 금지).
 *   1순위) SKU 이미지        sku.imageUrl
 *   2순위) 상품 대표 이미지   product.mainImageUrl  (목록 화면은 SKU에 비정규화된 productMainImageUrl 사용)
 *   3순위) 기본 이미지        NO_IMAGE
 */
export const NO_IMAGE = '/no-image.svg'

/**
 * @param {object|null} sku      SKU 문서 (imageUrl, productMainImageUrl)
 * @param {object|null} product  상품 문서 (mainImageUrl) - 있으면 최신값 우선
 * @returns {string} 이미지 URL
 */
export function resolveImage(sku, product = null) {
  if (sku?.imageUrl) return sku.imageUrl
  if (product?.mainImageUrl) return product.mainImageUrl
  if (sku?.productMainImageUrl) return sku.productMainImageUrl
  return NO_IMAGE
}

/** 상품 단독 이미지 (상품 목록 등) */
export function resolveProductImage(product) {
  return product?.mainImageUrl || NO_IMAGE
}
