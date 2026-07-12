<script setup>
import { ref, nextTick, onMounted, onUnmounted, watch } from 'vue'
import { getToken } from '@/api'
import { inboundStock, outboundStock } from '@/services/db'

const BASE = (import.meta.env.VITE_API_BASE || 'http://localhost:8080/api').replace(/\/$/, '')

const open = ref(false)
const input = ref('')
const messages = ref([])
const loading = ref(false)
const pendingImage = ref(null)
const isRecording = ref(false)
const messagesEl = ref(null)
const fileInput = ref(null)
const cameraInput = ref(null)

let msgId = 0
const newId = () => ++msgId

onMounted(() => {
  messages.value.push({
    id: newId(), role: 'assistant',
    text: '안녕하세요! AI 입출고 어시스턴트입니다.\n사진을 찍거나 텍스트/음성으로 입고·출고를 요청해주세요.',
  })
})

// 모바일에서 채팅 열면 body 스크롤 방지
watch(open, (v) => { document.body.style.overflow = v ? 'hidden' : '' })
onUnmounted(() => { document.body.style.overflow = '' })

function scrollToBottom() {
  nextTick(() => { if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight })
}

/* ============ 카메라 / 갤러리 ============ */
function triggerCamera() { cameraInput.value?.click() }
function triggerGallery() { fileInput.value?.click() }
function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (!file) return
  // 이미지 리사이즈 (max 800px) → base64
  const img = new Image()
  img.onload = () => {
    const max = 800
    let w = img.width, h = img.height
    if (w > max || h > max) {
      const ratio = Math.min(max / w, max / h)
      w = Math.round(w * ratio); h = Math.round(h * ratio)
    }
    const canvas = document.createElement('canvas')
    canvas.width = w; canvas.height = h
    canvas.getContext('2d').drawImage(img, 0, 0, w, h)
    pendingImage.value = canvas.toDataURL('image/jpeg', 0.85)
    URL.revokeObjectURL(img.src)
  }
  img.src = URL.createObjectURL(file)
  e.target.value = ''
}
function removePendingImage() { pendingImage.value = null }

/* ============ 음성 입력 (Web Speech API) ============ */
const SpeechRecognition = typeof window !== 'undefined' ? (window.SpeechRecognition || window.webkitSpeechRecognition) : null
let recognition = null

function toggleMic() {
  if (isRecording.value) { recognition?.stop(); isRecording.value = false; return }
  if (!SpeechRecognition) return
  recognition = new SpeechRecognition()
  recognition.lang = 'ko-KR'
  recognition.continuous = false
  recognition.interimResults = true
  recognition.onresult = (ev) => {
    const r = ev.results[ev.results.length - 1]
    input.value = r[0].transcript
    if (r.isFinal) { isRecording.value = false; send() }
  }
  recognition.onerror = () => { isRecording.value = false }
  recognition.onend = () => { isRecording.value = false }
  recognition.start()
  isRecording.value = true
}

/* ============ 메시지 전송 ============ */
async function send() {
  const text = input.value.trim()
  const image = pendingImage.value
  if (!text && !image) return
  if (loading.value) return

  messages.value.push({ id: newId(), role: 'user', text, image })
  input.value = ''; pendingImage.value = null
  scrollToBottom()

  const assistantMsg = { id: newId(), role: 'assistant', text: '', action: null, actionExecuted: false }
  messages.value.push(assistantMsg)
  loading.value = true
  scrollToBottom()

  try {
    // 대화 이력 구성 (시스템 메시지 제외)
    const history = messages.value
      .filter(m => (m.role === 'user' || m.role === 'assistant') && m.id !== assistantMsg.id)
      .filter(m => m.text || m.image)
      .map(m => ({
        role: m.role,
        text: m.text || '',
        ...(m.role === 'user' && m.image ? { imageBase64: m.image } : {}),
      }))

    const resp = await fetch(`${BASE}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
      body: JSON.stringify({ messages: history }),
    })

    if (!resp.ok) {
      assistantMsg.text = '오류가 발생했습니다. 다시 시도해주세요.'
      return
    }

    // SSE 스트림 읽기
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        for (const line of part.split('\n')) {
          if (!line.startsWith('data: ')) continue
          try {
            const d = JSON.parse(line.substring(6))
            if (d.type === 'delta') { assistantMsg.text += d.text; scrollToBottom() }
            else if (d.type === 'done') { if (d.action && Object.keys(d.action).length) assistantMsg.action = d.action }
            else if (d.type === 'error') { assistantMsg.text += (assistantMsg.text ? '\n' : '') + d.text }
          } catch (_) { /* skip */ }
        }
      }
    }
    if (!assistantMsg.text && !assistantMsg.action) assistantMsg.text = '응답을 받지 못했습니다.'
  } catch (e) {
    assistantMsg.text = '네트워크 오류가 발생했습니다.'
  } finally {
    loading.value = false; scrollToBottom()
  }
}

/* ============ 액션 실행 ============ */
async function executeAction(msg) {
  if (!msg.action || msg.actionExecuted) return
  msg.actionExecuted = true
  const a = msg.action
  const resultMsg = { id: newId(), role: 'system', text: '처리 중...', success: null }
  messages.value.push(resultMsg); scrollToBottom()

  try {
    const rid = crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const aiMemo = '[AI] ' + (a.memo || '')
    let r
    if (a.type === 'inbound') {
      r = await inboundStock(a.skuId, a.storageLocationId, a.qty, aiMemo, a.reason || '', rid)
      resultMsg.text = `입고 완료! 재고 ${r.before} → ${r.after}개 (${r.delta > 0 ? '+' : ''}${r.delta}개)`
    } else if (a.type === 'outbound') {
      r = await outboundStock(a.stockId, a.qty, aiMemo, a.reason || '', {
        usagePlace: a.usagePlace || '', requestDept: a.requestDept || '',
        requester: a.requester || '', handler: a.handler || '',
      }, rid)
      resultMsg.text = `출고 완료! 재고 ${r.before} → ${r.after}개 (${r.delta}개)`
    }
    resultMsg.success = true
  } catch (e) {
    resultMsg.text = `처리 실패: ${e.message || '알 수 없는 오류'}`
    resultMsg.success = false
    msg.actionExecuted = false
  }
  scrollToBottom()
}

function dismissAction(msg) { msg.action = null }

function clearChat() {
  messages.value = [{ id: newId(), role: 'assistant', text: '대화가 초기화되었습니다. 무엇을 도와드릴까요?' }]
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send() }
}
</script>

<template>
  <Teleport to="body">
    <!-- 플로팅 버튼 -->
    <button
      v-if="!open" @click="open = true"
      class="no-print fixed bottom-[72px] right-4 z-40 flex h-14 w-14 items-center justify-center rounded-full bg-brand-600 text-white shadow-lg transition hover:bg-brand-700 active:scale-95 lg:bottom-6"
      title="AI 어시스턴트"
    >
      <svg class="h-7 w-7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
        <circle cx="8.5" cy="10" r="0.8" fill="currentColor" /><circle cx="12" cy="10" r="0.8" fill="currentColor" /><circle cx="15.5" cy="10" r="0.8" fill="currentColor" />
      </svg>
    </button>

    <!-- 채팅 창 -->
    <div v-if="open" class="fixed inset-0 z-50 flex flex-col bg-white lg:inset-auto lg:bottom-6 lg:right-4 lg:h-[650px] lg:w-[400px] lg:rounded-2xl lg:border lg:border-slate-200 lg:shadow-2xl">
      <!-- 헤더 -->
      <div class="flex shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4 py-3 lg:rounded-t-2xl">
        <div class="flex items-center gap-2">
          <div class="flex h-8 w-8 items-center justify-center rounded-lg bg-brand-600 text-xs font-bold text-white">AI</div>
          <div><p class="text-sm font-semibold text-slate-800">AI 어시스턴트</p><p class="text-[11px] text-slate-400">입출고 도우미</p></div>
        </div>
        <div class="flex items-center gap-0.5">
          <button @click="clearChat" class="rounded-lg p-2 text-slate-400 hover:bg-slate-100" title="초기화">
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 12a9 9 0 0115.5-6.3L21 8M21 3v5h-5M21 12a9 9 0 01-15.5 6.3L3 16M3 21v-5h5" /></svg>
          </button>
          <button @click="open = false" class="rounded-lg p-2 text-slate-400 hover:bg-slate-100" title="닫기">
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </div>

      <!-- 메시지 영역 -->
      <div ref="messagesEl" class="flex-1 overflow-y-auto p-4 space-y-3 scrollbar-slim bg-slate-50">
        <div v-for="msg in messages" :key="msg.id">
          <!-- 사용자 -->
          <div v-if="msg.role === 'user'" class="flex justify-end">
            <div class="max-w-[80%] space-y-1">
              <img v-if="msg.image" :src="msg.image" class="ml-auto h-32 rounded-xl border border-slate-200 object-cover" />
              <div v-if="msg.text" class="rounded-2xl rounded-br-md bg-brand-600 px-4 py-2.5 text-sm text-white whitespace-pre-wrap">{{ msg.text }}</div>
            </div>
          </div>

          <!-- 어시스턴트 -->
          <div v-else-if="msg.role === 'assistant'" class="flex justify-start">
            <div class="max-w-[85%] space-y-2">
              <div v-if="msg.text || (loading && msg === messages[messages.length - 1])" class="rounded-2xl rounded-bl-md bg-white px-4 py-2.5 text-sm text-slate-700 shadow-sm whitespace-pre-wrap">
                {{ msg.text }}<span v-if="loading && msg === messages[messages.length - 1] && !msg.action" class="inline-block h-4 w-0.5 animate-pulse bg-brand-500 align-middle ml-0.5" />
              </div>
              <!-- 액션 카드 -->
              <div v-if="msg.action && !msg.actionExecuted" class="rounded-xl border border-brand-200 bg-white p-3 shadow-sm">
                <p class="mb-1.5 text-xs font-bold" :class="msg.action.type === 'inbound' ? 'text-emerald-600' : 'text-sky-600'">
                  {{ msg.action.type === 'inbound' ? '입고 제안' : '출고 제안' }}
                </p>
                <div class="mb-3 space-y-0.5 text-xs text-slate-600">
                  <p>수량: <b>{{ msg.action.qty }}개</b></p>
                  <p>사유: {{ msg.action.reason }}</p>
                  <p v-if="msg.action.memo">메모: {{ msg.action.memo }}</p>
                </div>
                <div class="flex gap-2">
                  <button @click="executeAction(msg)" class="flex-1 rounded-lg bg-brand-600 py-2 text-xs font-medium text-white hover:bg-brand-700">확인 처리</button>
                  <button @click="dismissAction(msg)" class="flex-1 rounded-lg border border-slate-200 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50">취소</button>
                </div>
              </div>
              <div v-if="msg.actionExecuted" class="rounded-lg bg-emerald-50 px-3 py-1.5 text-[11px] text-emerald-600">처리 완료</div>
            </div>
          </div>

          <!-- 시스템 (결과) -->
          <div v-else-if="msg.role === 'system'" class="flex justify-center">
            <div class="rounded-xl px-4 py-2 text-xs font-medium shadow-sm"
              :class="msg.success === true ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : msg.success === false ? 'bg-rose-50 text-rose-600 border border-rose-100' : 'bg-white text-slate-500 border border-slate-100'">
              {{ msg.text }}
            </div>
          </div>
        </div>
      </div>

      <!-- 이미지 미리보기 -->
      <div v-if="pendingImage" class="shrink-0 border-t border-slate-100 bg-white px-4 py-2">
        <div class="relative inline-block">
          <img :src="pendingImage" class="h-16 rounded-lg border border-slate-200 object-cover" />
          <button @click="removePendingImage" class="absolute -right-1.5 -top-1.5 flex h-5 w-5 items-center justify-center rounded-full bg-slate-700 text-white hover:bg-slate-900">
            <svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </div>

      <!-- 입력 영역 -->
      <div class="shrink-0 border-t border-slate-200 bg-white p-3 lg:rounded-b-2xl">
        <div class="flex items-end gap-2">
          <div class="flex gap-0.5">
            <button @click="triggerCamera" class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600" title="카메라 촬영">
              <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z" /><circle cx="12" cy="13" r="4" />
              </svg>
            </button>
            <button @click="triggerGallery" class="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600" title="사진 선택">
              <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="8.5" cy="8.5" r="1.5" /><path d="M21 15l-5-5L5 21" />
              </svg>
            </button>
            <button @click="toggleMic" class="rounded-lg p-2 transition" :class="isRecording ? 'bg-rose-100 text-rose-600 animate-pulse' : 'text-slate-400 hover:bg-slate-100 hover:text-slate-600'" title="음성 입력">
              <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" /><path d="M19 10v2a7 7 0 01-14 0v-2M12 19v4M8 23h8" />
              </svg>
            </button>
          </div>
          <textarea
            v-model="input" @keydown="onKeydown" :disabled="loading"
            class="input flex-1 resize-none text-sm" rows="1"
            placeholder="메시지를 입력하세요..."
            style="min-height:40px;max-height:100px"
          />
          <button @click="send" :disabled="loading || (!input.trim() && !pendingImage)"
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-brand-600 text-white transition hover:bg-brand-700 disabled:opacity-40">
            <svg v-if="!loading" class="h-5 w-5" viewBox="0 0 24 24" fill="currentColor"><path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" /></svg>
            <svg v-else class="h-5 w-5 animate-spin" viewBox="0 0 24 24" fill="none"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" /><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" /></svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 숨김 파일 입력 -->
    <input ref="cameraInput" type="file" accept="image/*" capture="environment" class="hidden" @change="onFileSelected" />
    <input ref="fileInput" type="file" accept="image/*" class="hidden" @change="onFileSelected" />
  </Teleport>
</template>
