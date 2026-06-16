// SKU 규격 표시 공용 헬퍼
// 신규 치수(W/L/H/D cm)가 있으면 그것을, 없으면 기존 자유입력 spec 을 사용

export function dimText(s) {
  if (!s) return ''
  const p = []
  if (s.dimW) p.push(`W${s.dimW}`)
  if (s.dimL) p.push(`L${s.dimL}`)
  if (s.dimH) p.push(`H${s.dimH}`)
  if (s.dimD) p.push(`D${s.dimD}`)
  return p.length ? p.join(' × ') + ' cm' : ''
}

// 규격 표시 텍스트: 치수 우선, 없으면 spec
export function specText(s) {
  return dimText(s) || (s && s.spec) || ''
}
