-- 엠파크 WMS — 감사로그(누가 무엇을 바꿨나)
-- 기준정보/상품관리/위치관리/입출고/재고 변경을 한 테이블에 통합 기록

create table if not exists public.audit_logs (
  id uuid primary key default gen_random_uuid(),
  module text not null,        -- 기준정보 | 상품관리 | 위치관리 | 입/출고관리 | 재고관리
  table_name text,
  action text not null,        -- 생성 | 수정 | 삭제 | 입고 | 출고 | 재고조정 | 재고실사 | 위치변경 | 교체
  row_id uuid,
  label text,                  -- 코드 또는 이름
  by_user_id uuid,
  by_name text,
  at timestamptz not null default now()
);
create index if not exists idx_audit_at on public.audit_logs(at desc);
create index if not exists idx_audit_user_at on public.audit_logs(by_user_id, at desc);
create index if not exists idx_audit_module_at on public.audit_logs(module, at desc);

alter table public.audit_logs enable row level security;
grant select on public.audit_logs to authenticated;
drop policy if exists "audit read admin" on public.audit_logs;
create policy "audit read admin" on public.audit_logs for select to authenticated using (public.is_admin());

-- 공통 감사 트리거 (TG_ARGV[0] = 모듈명). audit.skip='1' 이면 건너뜀(스톡 RPC에서 중복 방지)
create or replace function public.audit_trigger()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_name text;
  v_action text;
  v_row jsonb;
begin
  if coalesce(current_setting('audit.skip', true), '') = '1' then
    return coalesce(new, old);
  end if;
  if v_uid is not null then select display_name into v_name from public.profiles where id = v_uid; end if;
  if tg_op = 'INSERT' then v_action := '생성'; v_row := to_jsonb(new);
  elsif tg_op = 'UPDATE' then v_action := '수정'; v_row := to_jsonb(new);
  else v_action := '삭제'; v_row := to_jsonb(old);
  end if;
  insert into public.audit_logs(module, table_name, action, row_id, label, by_user_id, by_name)
  values (tg_argv[0], tg_table_name, v_action,
          (v_row->>'id')::uuid,
          coalesce(v_row->>'code', v_row->>'name', ''),
          v_uid, v_name);
  return coalesce(new, old);
end $$;

-- 트리거 부착
drop trigger if exists audit_complexes on public.complexes;
create trigger audit_complexes after insert or update or delete on public.complexes for each row execute function public.audit_trigger('기준정보');
drop trigger if exists audit_categories on public.categories;
create trigger audit_categories after insert or update or delete on public.categories for each row execute function public.audit_trigger('기준정보');
drop trigger if exists audit_product_codes on public.product_codes;
create trigger audit_product_codes after insert or update or delete on public.product_codes for each row execute function public.audit_trigger('기준정보');
drop trigger if exists audit_product_details on public.product_details;
create trigger audit_product_details after insert or update or delete on public.product_details for each row execute function public.audit_trigger('기준정보');

drop trigger if exists audit_products on public.products;
create trigger audit_products after insert or update or delete on public.products for each row execute function public.audit_trigger('상품관리');
drop trigger if exists audit_skus on public.skus;
create trigger audit_skus after insert or update or delete on public.skus for each row execute function public.audit_trigger('상품관리');

drop trigger if exists audit_zones on public.zones;
create trigger audit_zones after insert or update or delete on public.zones for each row execute function public.audit_trigger('위치관리');
drop trigger if exists audit_sub_zones on public.sub_zones;
create trigger audit_sub_zones after insert or update or delete on public.sub_zones for each row execute function public.audit_trigger('위치관리');
drop trigger if exists audit_storage_locations on public.storage_locations;
create trigger audit_storage_locations after insert or update or delete on public.storage_locations for each row execute function public.audit_trigger('위치관리');

-- 헬퍼: 감사로그 직접 기록 (스톡 RPC 용)
create or replace function public.log_audit(p_module text, p_action text, p_row_id uuid, p_label text, p_uid uuid, p_name text)
returns void language sql security definer set search_path = public as $$
  insert into public.audit_logs(module, table_name, action, row_id, label, by_user_id, by_name)
  values (p_module, 'skus', p_action, p_row_id, p_label, p_uid, p_name);
$$;

/* ===== 스톡 RPC 재정의: 스톡 변경은 트리거 대신 직접 감사기록 ===== */
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
  if v_val < 0 then raise exception '수량을 올바르게 입력하세요.'; end if;
  perform set_config('audit.skip', '1', true); -- skus 업데이트 트리거 중복기록 방지
  select display_name into v_actor from public.profiles where id = v_uid;
  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  v_before := coalesce(s.qty,0);
  if p_type = 'in' then v_delta := v_val; v_after := v_before + v_val;
  elsif p_type = 'out' then
    if v_before < v_val then raise exception '재고 부족: 현재 %개', v_before; end if;
    v_delta := -v_val; v_after := v_before - v_val;
  elsif p_type in ('adjust','audit') then v_after := v_val; v_delta := v_after - v_before;
  else raise exception '알 수 없는 작업 유형'; end if;
  v_safety := coalesce(s.safety_stock,0);

  update public.skus set
    qty = v_after,
    status = case when v_after <= 0 then 'out' when v_safety > 0 and v_after <= v_safety then 'low' else 'in_stock' end,
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

  perform public.log_audit(
    case when p_type in ('in','out') then '입/출고관리' else '재고관리' end,
    case p_type when 'in' then '입고' when 'out' then '출고' when 'adjust' then '재고조정' else '재고실사' end,
    p_sku_id, s.code || ' (' || v_before || '→' || v_after || ')', v_uid, v_actor);

  return json_build_object('before', v_before, 'after', v_after, 'delta', v_delta);
end $$;

create or replace function public.replace_lifecycle(p_sku_id uuid, p_reason text default '')
returns json
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_actor text;
  s public.skus%rowtype;
  v_next timestamptz;
  v_now timestamptz := now();
begin
  if v_uid is null then raise exception '로그인이 필요합니다.'; end if;
  perform set_config('audit.skip', '1', true);
  select display_name into v_actor from public.profiles where id = v_uid;
  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  if s.lifecycle_enabled then
    v_next := case lower(coalesce(s.cycle_unit,'month'))
      when 'day'  then v_now + (coalesce(s.cycle_value,0) || ' days')::interval
      when 'year' then v_now + (coalesce(s.cycle_value,0) || ' years')::interval
      else             v_now + (coalesce(s.cycle_value,0) || ' months')::interval
    end;
  else v_next := null; end if;

  update public.skus set last_replaced_at = v_now, next_replace_at = v_next, last_replaced_by = v_actor where id = p_sku_id;

  insert into public.lifecycle_logs
    (sku_id, sku_code, product_name, replaced_at, next_replace_at, reason, path_label, complex_name, by_user_id, by_name)
  values
    (p_sku_id, s.code, s.product_name, v_now, v_next,
     coalesce(nullif(p_reason,''), s.replace_reason, ''), s.path_label, s.complex_name, v_uid, v_actor);

  perform public.log_audit('재고관리', '교체', p_sku_id, s.code, v_uid, v_actor);
  return json_build_object('replacedAt', v_now, 'nextReplaceAt', v_next);
end $$;

create or replace function public.set_location(
  p_sku_id uuid, p_storage_location_id uuid default null, p_storage_location_code text default '',
  p_zone_id uuid default null, p_zone_name text default '',
  p_sub_zone_id uuid default null, p_sub_zone_name text default '', p_location_label text default ''
) returns json
language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_actor text;
  s public.skus%rowtype;
  v_changed boolean;
begin
  if v_uid is null then raise exception '로그인이 필요합니다.'; end if;
  if not public.is_admin() then raise exception '위치 변경 권한이 없습니다.'; end if;
  perform set_config('audit.skip', '1', true);
  select display_name into v_actor from public.profiles where id = v_uid;
  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  v_changed := coalesce(s.storage_location_id::text,'') is distinct from coalesce(p_storage_location_id::text,'')
            or coalesce(s.location_label,'') is distinct from coalesce(p_location_label,'');

  update public.skus set
    storage_location_id = p_storage_location_id, storage_location_code = coalesce(p_storage_location_code,''),
    zone_id = p_zone_id, zone_name = coalesce(p_zone_name,''),
    sub_zone_id = p_sub_zone_id, sub_zone_name = coalesce(p_sub_zone_name,''),
    location_label = coalesce(p_location_label,'')
  where id = p_sku_id;

  if v_changed then
    insert into public.location_logs
      (sku_id, sku_code, product_name, from_label, to_label,
       storage_location_id, storage_location_code, location_label, complex_name, by_user_id, by_name)
    values
      (p_sku_id, s.code, s.product_name,
       nullif(coalesce(s.location_label,''),''), nullif(coalesce(p_location_label,''),''),
       p_storage_location_id, coalesce(p_storage_location_code,''), coalesce(p_location_label,''),
       s.complex_name, v_uid, v_actor);
    perform public.log_audit('재고관리', '위치변경', p_sku_id, s.code, v_uid, v_actor);
  end if;
  return json_build_object('ok', true, 'changed', v_changed);
end $$;

/* ===== 대시보드 집계 RPC ===== */
create or replace function public.audit_top_users(p_limit int default 10)
returns table(name text, cnt bigint)
language sql security definer set search_path = public as $$
  select coalesce(by_name,'(알수없음)') as name, count(*) as cnt
  from public.audit_logs where action = '생성'
  group by by_name order by count(*) desc limit p_limit;
$$;

create or replace function public.top_products_by_sku(p_limit int default 10)
returns table(product_id uuid, product_name text, sku_count bigint)
language sql security definer set search_path = public as $$
  select product_id, max(product_name) as product_name, count(*) as sku_count
  from public.skus group by product_id order by count(*) desc limit p_limit;
$$;

create or replace function public.top_changed_skus(p_limit int default 10)
returns table(row_id uuid, label text, cnt bigint)
language sql security definer set search_path = public as $$
  select row_id, max(label) as label, count(*) as cnt
  from public.audit_logs where table_name = 'skus' and row_id is not null
  group by row_id order by count(*) desc limit p_limit;
$$;

grant execute on function public.audit_top_users(int) to authenticated;
grant execute on function public.top_products_by_sku(int) to authenticated;
grant execute on function public.top_changed_skus(int) to authenticated;
grant execute on function public.set_location(uuid, uuid, text, uuid, text, uuid, text, text) to authenticated;
grant execute on function public.apply_stock(uuid, text, numeric, text, text) to authenticated;
grant execute on function public.replace_lifecycle(uuid, text) to authenticated;
