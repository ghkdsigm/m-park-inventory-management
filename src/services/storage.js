import { api } from '@/api'

/**
 * 이미지 업로드/삭제 (Spring 백엔드 /api/storage).
 * - 업로드 전 클라이언트에서 리사이즈/압축 (기존 동작 유지)
 * - 서버가 디스크에 저장하고 공개 URL(/files/...) 을 반환
 */
function loadImage(file) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = URL.createObjectURL(file)
  })
}

async function compress(file, max = 1024, quality = 0.82) {
  const img = await loadImage(file)
  const scale = Math.min(1, max / Math.max(img.width, img.height))
  const w = Math.round(img.width * scale)
  const h = Math.round(img.height * scale)
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  canvas.getContext('2d').drawImage(img, 0, 0, w, h)
  URL.revokeObjectURL(img.src)
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
}

/**
 * @param {File} file
 * @param {string} prefix 저장 경로 접두(예: 'products', 'skus')
 * @returns {{url:string, path:string}}
 */
export async function uploadImage(file, prefix = 'images') {
  if (!file || !file.type?.startsWith('image/')) throw new Error('이미지 파일만 업로드할 수 있습니다.')
  const blob = await compress(file)
  const form = new FormData()
  form.append('file', blob, `${Date.now()}.jpg`)
  form.append('prefix', prefix)
  return api.upload('/storage/upload', form) // { url, path }
}

/** URL 로 Storage 객체 삭제. 실패해도 무시 */
export async function deleteImageByUrl(url) {
  if (!url) return
  try {
    await api.del('/storage', { url })
  } catch (e) {
    // 무시
  }
}
