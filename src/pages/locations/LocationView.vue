<script setup>
import { ref, onMounted } from 'vue'
import { complexes, zones, subZones } from '@/services/db'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import PageHeader from '@/components/ui/PageHeader.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

const complexList = ref([])
const zoneList = ref([])
const subList = ref([])
const selComplex = ref(null) // {id,name}
const selZone = ref(null)
const loadingZones = ref(false)
const loadingSubs = ref(false)

const newZone = ref('')
const newSub = ref('')
const editZoneId = ref('')
const editZoneName = ref('')
const editSubId = ref('')
const editSubName = ref('')

onMounted(async () => {
  try {
    complexList.value = await complexes.list()
  } catch (e) {
    toast.error('단지 목록을 불러오지 못했습니다.')
  }
})

/* ---- 단지 선택 ---- */
async function selectComplex(c) {
  selComplex.value = c
  selZone.value = null
  subList.value = []
  await loadZones()
}
async function loadZones() {
  if (!selComplex.value) return
  loadingZones.value = true
  try {
    zoneList.value = await zones.listByComplex(selComplex.value.id)
  } finally {
    loadingZones.value = false
  }
}

/* ---- 구역 CRUD ---- */
async function addZone() {
  const name = newZone.value.trim()
  if (!name) return
  try {
    await zones.create({ name, complexId: selComplex.value.id, complexName: selComplex.value.name })
    newZone.value = ''
    await loadZones()
    toast.success('구역이 추가되었습니다.')
  } catch (e) {
    toast.error('추가 실패: ' + (e.message || e.code))
  }
}
function startEditZone(z) {
  editZoneId.value = z.id
  editZoneName.value = z.name
}
async function saveZone(z) {
  const name = editZoneName.value.trim()
  if (!name) return
  try {
    await zones.update(z.id, { name })
    editZoneId.value = ''
    await loadZones()
    if (selZone.value?.id === z.id) selZone.value = { ...selZone.value, name }
    toast.success('수정되었습니다.')
  } catch (e) {
    toast.error('수정 실패: ' + (e.message || e.code))
  }
}
async function removeZone(z) {
  const ok = await confirm.value.ask({
    title: '구역 삭제',
    message: `"${z.name}" 구역과 그 하위 상세구역을 모두 삭제할까요?`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await zones.remove(z.id)
    if (selZone.value?.id === z.id) {
      selZone.value = null
      subList.value = []
    }
    await loadZones()
    toast.success('삭제되었습니다.')
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}

/* ---- 상세구역 선택/CRUD ---- */
async function selectZone(z) {
  selZone.value = z
  await loadSubs()
}
async function loadSubs() {
  if (!selZone.value) return
  loadingSubs.value = true
  try {
    subList.value = await subZones.listByZone(selZone.value.id)
  } finally {
    loadingSubs.value = false
  }
}
async function addSub() {
  const name = newSub.value.trim()
  if (!name) return
  try {
    await subZones.create({
      name,
      zoneId: selZone.value.id,
      zoneName: selZone.value.name,
      complexId: selComplex.value.id,
      complexName: selComplex.value.name,
    })
    newSub.value = ''
    await loadSubs()
    toast.success('상세구역이 추가되었습니다.')
  } catch (e) {
    toast.error('추가 실패: ' + (e.message || e.code))
  }
}
function startEditSub(s) {
  editSubId.value = s.id
  editSubName.value = s.name
}
async function saveSub(s) {
  const name = editSubName.value.trim()
  if (!name) return
  try {
    await subZones.update(s.id, { name })
    editSubId.value = ''
    await loadSubs()
    toast.success('수정되었습니다.')
  } catch (e) {
    toast.error('수정 실패: ' + (e.message || e.code))
  }
}
async function removeSub(s) {
  const ok = await confirm.value.ask({
    title: '상세구역 삭제',
    message: `"${s.name}" 상세구역을 삭제할까요?`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await subZones.remove(s.id)
    await loadSubs()
    toast.success('삭제되었습니다.')
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="위치코드관리" subtitle="단지 › 구역 › 상세구역 3단계 위치 코드(분류)를 관리합니다." />

    <div class="grid gap-3 lg:grid-cols-3">
      <!-- 1. 단지 -->
      <div class="card flex flex-col">
        <div class="flex items-center justify-between border-b border-slate-100 px-4 py-2.5">
          <h3 class="text-sm font-bold text-slate-700">① 단지</h3>
          <span class="text-xs text-slate-400">{{ complexList.length }}</span>
        </div>
        <div class="max-h-[60vh] flex-1 overflow-y-auto p-2 scrollbar-slim">
          <p v-if="!complexList.length" class="p-6 text-center text-sm text-slate-400">단지가 없습니다.</p>
          <button
            v-for="c in complexList"
            :key="c.id"
            class="mb-1 flex w-full items-center justify-between rounded-lg px-3 py-2 text-left text-sm hover:bg-slate-50"
            :class="selComplex?.id === c.id ? 'bg-brand-50 font-semibold text-brand-700' : 'text-slate-700'"
            @click="selectComplex(c)"
          >
            <span class="truncate">{{ c.name }}</span>
            <svg class="h-4 w-4 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </div>
      </div>

      <!-- 2. 구역 -->
      <div class="card flex flex-col" :class="!selComplex ? 'opacity-50' : ''">
        <div class="flex items-center justify-between border-b border-slate-100 px-4 py-2.5">
          <h3 class="text-sm font-bold text-slate-700">② 구역</h3>
          <span class="text-xs text-slate-400">{{ selComplex ? selComplex.name : '단지 선택' }}</span>
        </div>
        <div v-if="selComplex" class="flex gap-1.5 border-b border-slate-100 p-2">
          <input v-model="newZone" class="input" placeholder="구역명 입력 후 +" @keyup.enter="run(addZone)" />
          <button class="btn-primary btn-sm shrink-0" :disabled="saving" @click="run(addZone)">＋</button>
        </div>
        <div class="max-h-[55vh] flex-1 overflow-y-auto p-2 scrollbar-slim">
          <p v-if="!selComplex" class="p-6 text-center text-sm text-slate-400">왼쪽에서 단지를 선택하세요.</p>
          <p v-else-if="loadingZones" class="p-6 text-center text-sm text-slate-400">불러오는 중…</p>
          <p v-else-if="!zoneList.length" class="p-6 text-center text-sm text-slate-400">구역이 없습니다. 위에서 추가하세요.</p>
          <div
            v-for="z in zoneList"
            :key="z.id"
            class="group mb-1 flex items-center gap-1 rounded-lg px-2 py-1.5"
            :class="selZone?.id === z.id ? 'bg-brand-50' : 'hover:bg-slate-50'"
          >
            <template v-if="editZoneId === z.id">
              <input v-model="editZoneName" class="input py-1" @keyup.enter="run(() => saveZone(z))" />
              <button class="btn-primary btn-sm shrink-0" :disabled="saving" @click="run(() => saveZone(z))">저장</button>
              <button class="btn-ghost btn-sm shrink-0" @click="editZoneId = ''">취소</button>
            </template>
            <template v-else>
              <button class="flex flex-1 items-center justify-between truncate text-left text-sm" :class="selZone?.id === z.id ? 'font-semibold text-brand-700' : 'text-slate-700'" @click="selectZone(z)">
                <span class="truncate">{{ z.name }}</span>
                <svg class="h-4 w-4 shrink-0 text-slate-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round"/></svg>
              </button>
              <button class="shrink-0 rounded p-1 text-slate-400 opacity-0 hover:bg-slate-200 group-hover:opacity-100" title="수정" @click="startEditZone(z)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z" stroke-linejoin="round"/></svg>
              </button>
              <button class="shrink-0 rounded p-1 text-slate-400 opacity-0 hover:bg-rose-50 hover:text-rose-600 group-hover:opacity-100" title="삭제" @click="removeZone(z)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14" stroke-linecap="round"/></svg>
              </button>
            </template>
          </div>
        </div>
      </div>

      <!-- 3. 상세구역 -->
      <div class="card flex flex-col" :class="!selZone ? 'opacity-50' : ''">
        <div class="flex items-center justify-between border-b border-slate-100 px-4 py-2.5">
          <h3 class="text-sm font-bold text-slate-700">③ 상세구역</h3>
          <span class="text-xs text-slate-400">{{ selZone ? selZone.name : '구역 선택' }}</span>
        </div>
        <div v-if="selZone" class="flex gap-1.5 border-b border-slate-100 p-2">
          <input v-model="newSub" class="input" placeholder="상세구역명 입력 후 +" @keyup.enter="run(addSub)" />
          <button class="btn-primary btn-sm shrink-0" :disabled="saving" @click="run(addSub)">＋</button>
        </div>
        <div class="max-h-[55vh] flex-1 overflow-y-auto p-2 scrollbar-slim">
          <p v-if="!selZone" class="p-6 text-center text-sm text-slate-400">가운데에서 구역을 선택하세요.</p>
          <p v-else-if="loadingSubs" class="p-6 text-center text-sm text-slate-400">불러오는 중…</p>
          <p v-else-if="!subList.length" class="p-6 text-center text-sm text-slate-400">상세구역이 없습니다. 위에서 추가하세요.</p>
          <div v-for="s in subList" :key="s.id" class="group mb-1 flex items-center gap-1 rounded-lg px-2 py-1.5 hover:bg-slate-50">
            <template v-if="editSubId === s.id">
              <input v-model="editSubName" class="input py-1" @keyup.enter="run(() => saveSub(s))" />
              <button class="btn-primary btn-sm shrink-0" :disabled="saving" @click="run(() => saveSub(s))">저장</button>
              <button class="btn-ghost btn-sm shrink-0" @click="editSubId = ''">취소</button>
            </template>
            <template v-else>
              <span class="flex-1 truncate text-sm text-slate-700">{{ s.name }}</span>
              <button class="shrink-0 rounded p-1 text-slate-400 opacity-0 hover:bg-slate-200 group-hover:opacity-100" title="수정" @click="startEditSub(s)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z" stroke-linejoin="round"/></svg>
              </button>
              <button class="shrink-0 rounded p-1 text-slate-400 opacity-0 hover:bg-rose-50 hover:text-rose-600 group-hover:opacity-100" title="삭제" @click="removeSub(s)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12h14" stroke-linecap="round"/></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <p class="mt-3 text-xs text-slate-400">💡 여기서 만든 구역/상세구역으로 <b>보관위치관리</b>에서 실제 보관위치를 만들고, <b>재고조정</b>에서 SKU에 지정 → <b>재고실사</b> 검증 → <b>재고현황</b> 조회로 이어집니다.</p>

    <ConfirmDialog ref="confirm" />
  </div>
</template>
