import { supabase } from '@/supabase'

/**
 * 이미지 업로드/삭제 (Supabase Storage, 버킷: images).
 * - 업로드 전 클라이언트에서 리사이즈/압축
 * - 공개 URL 을 Firestore 대신 PostgreSQL 컬럼에 저장
 */
const BUCKET = 'images'

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
  const name = `${Date.now()}-${Math.round(Math.random() * 1e6)}.jpg`
  const path = `${prefix}/${name}`
  const { error } = await supabase.storage.from(BUCKET).upload(path, blob, {
    contentType: 'image/jpeg',
    upsert: false,
  })
  if (error) throw error
  const { data } = supabase.storage.from(BUCKET).getPublicUrl(path)
  return { url: data.publicUrl, path }
}

/** 공개 URL 에서 버킷 내 경로 추출 */
function pathFromUrl(url) {
  const marker = `/storage/v1/object/public/${BUCKET}/`
  const i = url.indexOf(marker)
  return i === -1 ? null : url.slice(i + marker.length)
}

/** URL 로 Storage 객체 삭제. 실패해도 무시 */
export async function deleteImageByUrl(url) {
  if (!url) return
  const path = pathFromUrl(url)
  if (!path) return
  try {
    await supabase.storage.from(BUCKET).remove([path])
  } catch (e) {
    // 무시
  }
}
