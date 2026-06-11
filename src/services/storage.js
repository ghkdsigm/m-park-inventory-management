import { storage } from '@/firebase'
import { ref as storageRef, uploadBytes, getDownloadURL, deleteObject } from 'firebase/storage'

/**
 * 이미지 업로드/삭제 (Firebase Storage).
 * - 업로드 전에 클라이언트에서 리사이즈/압축 → 저장공간·전송량 절약
 * - 반환 URL 을 Firestore 문서에 저장해 사용
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
 * 이미지 업로드.
 * @param {File} file
 * @param {string} prefix  저장 경로 접두(예: 'products', 'skus')
 * @returns {{url:string, path:string}}
 */
export async function uploadImage(file, prefix = 'images') {
  if (!file || !file.type?.startsWith('image/')) throw new Error('이미지 파일만 업로드할 수 있습니다.')
  const blob = await compress(file)
  const name = `${Date.now()}-${Math.round(Math.random() * 1e6)}.jpg`
  const path = `${prefix}/${name}`
  const ref = storageRef(storage, path)
  await uploadBytes(ref, blob, { contentType: 'image/jpeg' })
  const url = await getDownloadURL(ref)
  return { url, path }
}

/** URL(다운로드 URL)로 Storage 객체 삭제. 실패해도 무시(고아 파일 허용) */
export async function deleteImageByUrl(url) {
  if (!url) return
  try {
    await deleteObject(storageRef(storage, url))
  } catch (e) {
    // 이미 없거나 권한 등 - 무시
  }
}
