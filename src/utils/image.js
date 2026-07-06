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

/**
 * 업로드 전 클라이언트 리사이즈/압축 — 큰 사진(휴대폰 수 MB)을 목록/상세에 충분한 크기로 줄여
 * 저장 용량·목록 로딩·대역폭 비용을 줄인다. GIF/SVG 는 원본 유지.
 * @param {File} file
 * @param {{maxDim?:number, quality?:number, skipUnder?:number}} opts
 * @returns {Promise<File>}
 */
export async function compressImage(file, { maxDim = 1280, quality = 0.82, skipUnder = 300 * 1024 } = {}) {
  try {
    if (!file || !file.type?.startsWith('image/')) return file
    if (file.type === 'image/gif' || file.type === 'image/svg+xml') return file // 애니메이션/벡터 보존
    const dataUrl = await new Promise((res, rej) => {
      const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(file)
    })
    const img = await new Promise((res, rej) => {
      const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = dataUrl
    })
    const bigSide = Math.max(img.width, img.height)
    if (bigSide <= maxDim && file.size <= skipUnder) return file // 이미 충분히 작음 → 그대로
    const scale = Math.min(1, maxDim / bigSide)
    const w = Math.round(img.width * scale), h = Math.round(img.height * scale)
    const canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, w, h) // 투명 PNG → JPEG 변환 시 흰 배경
    ctx.drawImage(img, 0, 0, w, h)
    const blob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', quality))
    if (!blob || blob.size >= file.size) return file // 오히려 커지면 원본 사용
    const name = (file.name || 'image').replace(/\.[^.]+$/, '') + '.jpg'
    return new File([blob], name, { type: 'image/jpeg', lastModified: file.lastModified })
  } catch (e) {
    return file // 실패 시 원본 업로드 (안전)
  }
}
