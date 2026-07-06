<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { complexes, zones, subZones, storageLocations } from '@/services/db'
import { useToast } from '@/composables/useToast'
import { useBusy } from '@/composables/useBusy'
import { usePagination } from '@/composables/usePagination'
import Pager from '@/components/ui/Pager.vue'
import BaseModal from '@/components/ui/BaseModal.vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import AppSelect from '@/components/ui/AppSelect.vue'

const toast = useToast()
const { busy: saving, run } = useBusy()
const confirm = ref(null)

const loading = ref(true)
const list = ref([])
const complexList = ref([])
const zonesAll = ref([])
const subsAll = ref([])
const search = ref('')
const filterComplex = ref('')
const fZone = ref('')
const fSub = ref('')

// 조회 연쇄 셀렉트 옵션
const zoneFilterOptions = computed(() => (filterComplex.value ? zonesAll.value.filter((z) => z.complexId === filterComplex.value) : zonesAll.value))
const subFilterOptions = computed(() => (fZone.value ? subsAll.value.filter((s) => s.zoneId === fZone.value) : []))
watch(filterComplex, () => { fZone.value = ''; fSub.value = '' })
watch(fZone, () => { fSub.value = '' })

async function load() {
  loading.value = true
  try {
    ;[list.value, complexList.value, zonesAll.value, subsAll.value] = await Promise.all([
      storageLocations.list(),
      complexes.list(),
      zones.listAll(),
      subZones.listAll(),
    ])
  } catch (e) {
    toast.error('불러오기 실패: ' + (e.message || e.code))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() =>
  list.value.filter((l) => {
    if (filterComplex.value && l.complexId !== filterComplex.value) return false
    if (fZone.value && l.zoneId !== fZone.value) return false
    if (fSub.value && l.subZoneId !== fSub.value) return false
    if (search.value) {
      const q = search.value.toLowerCase()
      return [l.code, l.name, l.complexName, l.locationLabel].some((v) => (v || '').toLowerCase().includes(q))
    }
    return true
  })
)
const { paged, page, pageSize, sizes, total, totalPages } = usePagination(filtered)

function fullLabel(l) {
  return [l.complexName, l.zoneName, l.subZoneName, l.name].filter(Boolean).join(' › ')
}

/* ---- 생성/수정 ---- */
const modal = ref(false)
const editing = ref(null)
const form = reactive({ code: '', complexId: '', zoneId: '', subZoneId: '', name: '' })

const formZones = computed(() => zonesAll.value.filter((z) => z.complexId === form.complexId))
const formSubs = computed(() => subsAll.value.filter((s) => s.zoneId === form.zoneId))
// 사용자가 직접 바꿀 때만 하위 초기화 (수정 팝업의 사전값이 지워지지 않도록 watch 대신 @change)
function onFormComplexChange() { form.zoneId = ''; form.subZoneId = '' }
function onFormZoneChange() { form.subZoneId = '' }

function openCreate() {
  if (!complexList.value.length) return toast.error('먼저 단지를 등록하세요.')
  editing.value = null
  Object.assign(form, { code: '', complexId: filterComplex.value || '', zoneId: '', subZoneId: '', name: '' })
  modal.value = true
}
function openEdit(l) {
  editing.value = l
  Object.assign(form, { code: l.code, complexId: l.complexId, zoneId: l.zoneId || '', subZoneId: l.subZoneId || '', name: l.name || '' })
  modal.value = true
}

async function save() {
  if (!form.complexId) return toast.error('단지(필수)를 선택하세요.')
  const complex = complexList.value.find((c) => c.id === form.complexId)
  const zone = zonesAll.value.find((z) => z.id === form.zoneId)
  const sub = subsAll.value.find((s) => s.id === form.subZoneId)
  const payload = {
    name: form.name.trim(),
    complexId: form.complexId,
    complexName: complex?.name || '',
    zoneId: form.zoneId || '',
    zoneName: zone?.name || '',
    subZoneId: form.subZoneId || '',
    subZoneName: sub?.name || '',
    // SKU 표시에 쓰는 라벨(단지 제외 - 단지는 별도 표시)
    locationLabel: [zone?.name, sub?.name, form.name.trim()].filter(Boolean).join(' > '),
  }
  try {
    if (editing.value) {
      await storageLocations.update(editing.value.id, payload)
      toast.success('수정되었습니다.')
    } else {
      const r = await storageLocations.create(payload)
      toast.success(`보관위치 생성 (${r.code})`)
    }
    modal.value = false
    await load()
  } catch (e) {
    toast.error('저장 실패: ' + (e.message || e.code))
  }
}

async function remove(l) {
  const ok = await confirm.value.ask({
    title: '보관위치 삭제',
    message: `"${fullLabel(l)}" (${l.code})를 삭제할까요?\n이 위치를 쓰던 SKU의 위치 표시는 남을 수 있으니 재조정하세요.`,
    confirmText: '삭제',
    danger: true,
  })
  if (!ok) return
  try {
    await storageLocations.remove(l.id)
    toast.success('삭제되었습니다.')
    await load()
  } catch (e) {
    toast.error('삭제 실패: ' + (e.message || e.code))
  }
}
</script>

<template>
  <div>
    <PageHeader title="보관위치관리" subtitle="실질적 최종 보관위치. 단지만 필수, 구역·상세구역은 선택. (SKU에 지정되는 위치)">
      <button class="btn-primary" @click="openCreate">+ 보관위치 추가</button>
    </PageHeader>

    <div class="no-print mb-3 flex flex-wrap items-center gap-2">
      <AppSelect v-model="filterComplex" class="w-auto">
        <option value="">전체 단지</option>
        <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
      </AppSelect>
      <AppSelect v-model="fZone" class="w-auto" :disabled="!filterComplex">
        <option value="">전체 구역</option>
        <option v-for="z in zoneFilterOptions" :key="z.id" :value="z.id">{{ z.name }}</option>
      </AppSelect>
      <AppSelect v-model="fSub" class="w-auto" :disabled="!fZone">
        <option value="">전체 상세구역</option>
        <option v-for="s in subFilterOptions" :key="s.id" :value="s.id">{{ s.name }}</option>
      </AppSelect>
      <input v-model="search" class="input w-full sm:w-64" placeholder="코드/위치명 검색" />
      <AppSelect v-model="pageSize" class="w-auto sm:ml-auto">
        <option v-for="n in sizes" :key="n" :value="n">{{ n }}개씩</option>
      </AppSelect>
    </div>

    <div class="card">
      <div class="overflow-x-auto scrollbar-slim">
      <div v-if="loading" class="p-8 text-center text-sm text-slate-400">불러오는 중…</div>
      <div v-else-if="!filtered.length" class="p-10 text-center text-sm text-slate-400">등록된 보관위치가 없습니다.</div>
      <table v-else class="w-full text-sm">
        <thead class="border-b border-slate-100 bg-slate-50 text-left text-xs text-slate-500">
          <tr>
            <th class="px-4 py-2.5 font-semibold">코드</th>
            <th class="px-4 py-2.5 font-semibold">보관위치 (단지 › 구역 › 상세구역 › 명칭)</th>
            <th class="px-4 py-2.5 text-right font-semibold">관리</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-for="l in paged" :key="l.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3"><span class="badge bg-brand-50 font-mono text-brand-700">{{ l.code }}</span></td>
            <td class="px-4 py-3">
              <span class="font-medium text-slate-800">📍 {{ fullLabel(l) }}</span>
            </td>
            <td class="px-4 py-3 text-right">
              <button class="btn-ghost btn-sm mr-1" @click="openEdit(l)">수정</button>
              <button class="btn-ghost btn-sm text-rose-600" @click="remove(l)">삭제</button>
            </td>
          </tr>
        </tbody>
      </table>
      </div>
      <Pager v-if="filtered.length" v-model:page="page" :total="total" :total-pages="totalPages" class="border-t border-slate-100" />
    </div>

    <BaseModal v-model="modal" :title="editing ? '보관위치 수정' : '보관위치 추가'">
      <div class="space-y-3">
        <div>
          <label class="label">보관위치 코드</label>
          <input v-if="editing" :value="form.code" class="input bg-slate-50 font-mono text-slate-400" readonly />
          <p v-else class="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">저장 시 자동 생성 (예: LOC-000001)</p>
        </div>
        <div>
          <label class="label">단지 <span class="text-rose-500">*</span></label>
          <AppSelect v-model="form.complexId" class="w-full" @change="onFormComplexChange">
            <option value="">단지 선택</option>
            <option v-for="c in complexList" :key="c.id" :value="c.id">{{ c.name }}</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">구역 <span class="text-slate-300">(선택)</span></label>
          <AppSelect v-model="form.zoneId" class="w-full" :disabled="!form.complexId" @change="onFormZoneChange">
            <option value="">선택 안 함</option>
            <option v-for="z in formZones" :key="z.id" :value="z.id">{{ z.name }}</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">상세구역 <span class="text-slate-300">(선택)</span></label>
          <AppSelect v-model="form.subZoneId" class="w-full" :disabled="!form.zoneId">
            <option value="">선택 안 함</option>
            <option v-for="s in formSubs" :key="s.id" :value="s.id">{{ s.name }}</option>
          </AppSelect>
        </div>
        <div>
          <label class="label">위치 명칭 <span class="text-slate-300">(선택, 예: 3번 선반/팔레트A)</span></label>
          <input v-model="form.name" class="input" placeholder="세부 명칭" />
        </div>
        <p class="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
          미리보기: 📍 {{ [complexList.find((c) => c.id === form.complexId)?.name, formZones.find((z) => z.id === form.zoneId)?.name, formSubs.find((s) => s.id === form.subZoneId)?.name, form.name].filter(Boolean).join(' › ') || '단지를 선택하세요' }}
        </p>
      </div>
      <template #footer>
        <button class="btn-ghost" @click="modal = false">취소</button>
        <button class="btn-primary" :disabled="saving" @click="run(save)">{{ editing ? '수정' : '생성' }}</button>
      </template>
    </BaseModal>

    <ConfirmDialog ref="confirm" />
  </div>
</template>
