import QRCode from 'qrcode'

/**
 * QR 유틸 (SKU 기준).
 * - QR 핵심값은 SKU 코드(예: TP001-10).
 * - 휴대폰 기본 카메라로도 바로 페이지가 열리도록 `/s/<SKU코드>` URL 형태로 인코딩합니다.
 *   (스캔 → SKU 조회 → 입고/출고/조정/실사)
 */

export function buildSkuUrl(skuCode) {
  return `${window.location.origin}/s/${encodeURIComponent(skuCode)}`
}

export async function makeQrDataUrl(skuCode, opts = {}) {
  return QRCode.toDataURL(buildSkuUrl(skuCode), {
    errorCorrectionLevel: 'M',
    margin: 1,
    scale: opts.scale || 6,
    color: { dark: '#0f172a', light: '#ffffff' },
  })
}

/** 여러 SKU의 QR을 일괄 생성 (라벨 출력용) */
export async function makeQrBatch(skuList) {
  const out = []
  for (const s of skuList) {
    out.push({ ...s, qrDataUrl: await makeQrDataUrl(s.code), scanUrl: buildSkuUrl(s.code) })
  }
  return out
}
