-- 엠파크 WMS — 입/출고 권한(can_stock) 추가
-- 일반 사용자는 기본 '조회만'. 관리자가 사용자관리에서 입/출고 권한을 부여하면 입고/출고 가능.
-- 조정/실사/기준정보 등은 여전히 admin 전용.

/* ---------- 권한 플래그 컬럼 ---------- */
alter table public.profiles
  add column if not exists can_stock boolean not null default false;

/* ---------- 입/출고 권한 체크 함수 (admin 은 항상 true) ---------- */
create or replace function public.has_stock_perm()
returns boolean language sql stable security definer set search_path = public as $$
  select exists(
    select 1 from public.profiles
    where id = auth.uid() and (role = 'admin' or can_stock = true)
  );
$$;
grant execute on function public.has_stock_perm() to authenticated;

/* ---------- apply_stock 에 서버측 권한 검증 추가 (재생성) ---------- */
create or replace function public.apply_stock(
  p_sku_id uuid, p_type text, p_value numeric, p_memo text default '', p_reason text default ''
) returns json
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_actor text;
  s public.skus%rowtype;
  v_before int; v_after int; v_delta int; v_safety int; v_val int := coalesce(p_value,0)::int;
begin
  if v_uid is null then raise exception '로그인이 필요합니다.'; end if;

  -- 권한: 입/출고는 입출고권한(or admin), 조정/실사는 admin
  if p_type in ('in','out') then
    if not public.has_stock_perm() then raise exception '입/출고 권한이 없습니다. 관리자에게 문의하세요.'; end if;
  elsif p_type in ('adjust','audit') then
    if not public.is_admin() then raise exception '재고조정/실사 권한이 없습니다.'; end if;
  end if;

  if v_val < 0 then raise exception '수량을 올바르게 입력하세요.'; end if;
  select display_name into v_actor from public.profiles where id = v_uid;

  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  v_before := coalesce(s.qty,0);
  if p_type = 'in' then
    v_delta := v_val; v_after := v_before + v_val;
  elsif p_type = 'out' then
    if v_before < v_val then raise exception '재고 부족: 현재 %개', v_before; end if;
    v_delta := -v_val; v_after := v_before - v_val;
  elsif p_type in ('adjust','audit') then
    v_after := v_val; v_delta := v_after - v_before;
  else
    raise exception '알 수 없는 작업 유형';
  end if;
  v_safety := coalesce(s.safety_stock,0);

  update public.skus set
    qty = v_after,
    status = case when v_after <= 0 then 'out'
                  when v_safety > 0 and v_after <= v_safety then 'low'
                  else 'in_stock' end,
    total_in = total_in + case when p_type='in' then v_val else 0 end,
    total_out = total_out + case when p_type='out' then v_val else 0 end,
    last_moved_by = v_actor, last_moved_at = now()
  where id = p_sku_id;

  insert into public.stock_movements
    (sku_id, sku_code, product_name, type, qty, delta, before, after,
     complex_id, complex_name, path_label, memo, reason, by_user_id, by_name)
  values
    (p_sku_id, s.code, s.product_name, p_type, abs(v_delta), v_delta, v_before, v_after,
     s.complex_id, s.complex_name, s.path_label, coalesce(p_memo,''), coalesce(p_reason,''), v_uid, v_actor);

  insert into public.daily_stats as d (date, move_count, in_count, out_count, adjust_count, audit_count, in_qty, out_qty, updated_at)
  values (current_date, 1,
    case when p_type='in' then 1 else 0 end, case when p_type='out' then 1 else 0 end,
    case when p_type='adjust' then 1 else 0 end, case when p_type='audit' then 1 else 0 end,
    case when p_type='in' then v_val else 0 end, case when p_type='out' then v_val else 0 end, now())
  on conflict (date) do update set
    move_count = d.move_count + 1,
    in_count = d.in_count + (case when p_type='in' then 1 else 0 end),
    out_count = d.out_count + (case when p_type='out' then 1 else 0 end),
    adjust_count = d.adjust_count + (case when p_type='adjust' then 1 else 0 end),
    audit_count = d.audit_count + (case when p_type='audit' then 1 else 0 end),
    in_qty = d.in_qty + (case when p_type='in' then v_val else 0 end),
    out_qty = d.out_qty + (case when p_type='out' then v_val else 0 end),
    updated_at = now();

  return json_build_object('before', v_before, 'after', v_after, 'delta', v_delta);
end $$;
