-- 엠파크 WMS — 입출고 취소(역분개) 지원
-- 원장(stock_movements)은 보존하고, 취소 시 '취소 전표'(type='void')를 추가로 기록한다.
-- 현장 자가정정: 본인이 등록한 당일 처리분만 취소 가능. 그 외(당일 경과/타인/재고변동)는 관리자 정정.

/* ---------- 컬럼 추가 ---------- */
alter table public.stock_movements
  add column if not exists voided boolean not null default false,
  add column if not exists voided_at timestamptz,
  add column if not exists voided_by text,
  add column if not exists void_reason text default '',
  add column if not exists reversal_of uuid references public.stock_movements(id) on delete set null;

-- type 제약에 'void' 추가
alter table public.stock_movements drop constraint if exists stock_movements_type_check;
alter table public.stock_movements
  add constraint stock_movements_type_check check (type in ('in','out','adjust','audit','void'));

/* ---------- 취소(역분개) 함수 ---------- */
create or replace function public.void_movement(p_movement_id uuid, p_reason text default '')
returns json
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_actor text;
  m public.stock_movements%rowtype;
  s public.skus%rowtype;
  v_rev_delta int;
  v_before int;
  v_after int;
  v_safety int;
begin
  if v_uid is null then raise exception '로그인이 필요합니다.'; end if;
  select display_name into v_actor from public.profiles where id = v_uid;

  select * into m from public.stock_movements where id = p_movement_id for update;
  if not found then raise exception '이력을 찾을 수 없습니다.'; end if;

  -- 취소 가능 조건 (본인 등록분 + 당일분 + 미취소 + 취소전표 아님)
  if m.type = 'void' then raise exception '취소 전표는 다시 취소할 수 없습니다.'; end if;
  if m.voided then raise exception '이미 취소된 처리입니다.'; end if;
  if m.by_user_id is distinct from v_uid then
    raise exception '본인이 등록한 처리만 취소할 수 있습니다. 관리자에게 정정을 요청하세요.';
  end if;
  if (m.at at time zone 'Asia/Seoul')::date <> (now() at time zone 'Asia/Seoul')::date then
    raise exception '당일 처리분만 취소할 수 있습니다. 관리자에게 정정을 요청하세요.';
  end if;

  select * into s from public.skus where id = m.sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  v_rev_delta := -m.delta;                 -- 원거래 효과를 상쇄
  v_before := coalesce(s.qty,0);
  v_after := v_before + v_rev_delta;
  if v_after < 0 then
    raise exception '그 사이 재고가 변경되어 취소할 수 없습니다. 관리자에게 정정을 요청하세요.';
  end if;
  v_safety := coalesce(s.safety_stock,0);

  -- 재고/누적 카운터 되돌림
  update public.skus set
    qty = v_after,
    status = case when v_after <= 0 then 'out'
                  when v_safety > 0 and v_after <= v_safety then 'low'
                  else 'in_stock' end,
    total_in = total_in - case when m.type='in' then m.qty else 0 end,
    total_out = total_out - case when m.type='out' then m.qty else 0 end,
    last_moved_by = v_actor, last_moved_at = now()
  where id = m.sku_id;

  -- 원거래에 취소 표시
  update public.stock_movements set
    voided = true, voided_at = now(), voided_by = v_actor, void_reason = coalesce(p_reason,'')
  where id = m.id;

  -- 취소(역분개) 전표 기록
  insert into public.stock_movements
    (sku_id, sku_code, product_name, type, qty, delta, before, after,
     complex_id, complex_name, path_label, memo, reason, by_user_id, by_name, reversal_of)
  values
    (m.sku_id, m.sku_code, m.product_name, 'void', abs(v_rev_delta), v_rev_delta, v_before, v_after,
     m.complex_id, m.complex_name, m.path_label, '', coalesce(nullif(p_reason,''), '취소'), v_uid, v_actor, m.id);

  -- 당일 집계에서 원거래 기여분 차감 (취소는 같은 날만 가능)
  update public.daily_stats set
    move_count = greatest(0, move_count - 1),
    in_count = greatest(0, in_count - case when m.type='in' then 1 else 0 end),
    out_count = greatest(0, out_count - case when m.type='out' then 1 else 0 end),
    adjust_count = greatest(0, adjust_count - case when m.type='adjust' then 1 else 0 end),
    audit_count = greatest(0, audit_count - case when m.type='audit' then 1 else 0 end),
    in_qty = greatest(0, in_qty - case when m.type='in' then m.qty else 0 end),
    out_qty = greatest(0, out_qty - case when m.type='out' then m.qty else 0 end),
    updated_at = now()
  where date = current_date;

  return json_build_object('before', v_before, 'after', v_after, 'delta', v_rev_delta);
end $$;
