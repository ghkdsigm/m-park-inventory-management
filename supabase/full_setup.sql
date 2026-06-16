-- ===== 엠파크 WMS 전체 셋업 =====

-- ---- 20260616090001_schema ----
-- 엠파크 WMS — 스키마 (테이블 / 시퀀스 / 자동코드·updated_at 트리거 / 프로필)
create extension if not exists pgcrypto;

/* ============================ 공통 트리거 함수 ============================ */
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at := now(); return new; end $$;

-- 시퀀스 기반 자동코드 (TG_ARGV: prefix, sequence_name)
create or replace function public.set_seq_code()
returns trigger language plpgsql as $$
begin
  if new.code is null or new.code = '' then
    new.code := tg_argv[0] || '-' || lpad(nextval(tg_argv[1])::text, 6, '0');
  end if;
  return new;
end $$;

/* ================================ 프로필 ================================ */
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text,
  display_name text,
  role text not null default 'user' check (role in ('admin','user')),
  created_at timestamptz not null default now()
);

-- 가입 시 프로필 자동 생성 (role=user)
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email, display_name, role)
  values (new.id, new.email, coalesce(new.raw_user_meta_data->>'display_name', new.email), 'user')
  on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created
  after insert on auth.users for each row execute function public.handle_new_user();

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists(select 1 from public.profiles where id = auth.uid() and role = 'admin');
$$;

/* ============================ 기준정보 (4단계) ============================ */
create table public.complexes (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  description text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);

create table public.categories (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  description text default '',
  complex_id uuid not null references public.complexes(id) on delete cascade,
  complex_name text default '',
  path_label text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);

create table public.product_codes (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  description text default '',
  category_id uuid not null references public.categories(id) on delete cascade,
  category_name text default '',
  complex_id uuid references public.complexes(id) on delete cascade,
  complex_name text default '',
  path_label text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);

create table public.product_details (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  description text default '',
  product_code_id uuid not null references public.product_codes(id) on delete cascade,
  product_code_name text default '',
  category_id uuid references public.categories(id) on delete cascade,
  category_name text default '',
  complex_id uuid references public.complexes(id) on delete cascade,
  complex_name text default '',
  path_label text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);

create sequence if not exists seq_categories;
create sequence if not exists seq_product_codes;
create sequence if not exists seq_product_details;

create trigger trg_cat_code before insert on public.categories
  for each row execute function public.set_seq_code('CTG','seq_categories');
create trigger trg_pc_code before insert on public.product_codes
  for each row execute function public.set_seq_code('PC','seq_product_codes');
create trigger trg_pd_code before insert on public.product_details
  for each row execute function public.set_seq_code('PCD','seq_product_details');

/* ============================ 위치 ============================ */
create table public.zones (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  complex_id uuid not null references public.complexes(id) on delete cascade,
  complex_name text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);
create table public.sub_zones (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  zone_id uuid not null references public.zones(id) on delete cascade,
  zone_name text default '',
  complex_id uuid references public.complexes(id) on delete cascade,
  complex_name text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);
create table public.storage_locations (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text default '',
  complex_id uuid not null references public.complexes(id) on delete cascade,
  complex_name text default '',
  zone_id uuid references public.zones(id) on delete set null,
  zone_name text default '',
  sub_zone_id uuid references public.sub_zones(id) on delete set null,
  sub_zone_name text default '',
  location_label text default '',
  created_at timestamptz not null default now(),
  updated_at timestamptz
);
create sequence if not exists seq_storage_locations;
create trigger trg_loc_code before insert on public.storage_locations
  for each row execute function public.set_seq_code('LOC','seq_storage_locations');

/* ============================ 상품 ============================ */
create table public.products (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  name text not null,
  maker text default '',
  barcode text default '',
  note text default '',
  main_image_url text default '',
  images jsonb default '[]'::jsonb,
  complex_id uuid not null references public.complexes(id) on delete restrict,
  complex_name text default '',
  category_id uuid references public.categories(id) on delete set null,
  category_name text default '',
  product_code_id uuid references public.product_codes(id) on delete set null,
  product_code_name text default '',
  product_detail_id uuid references public.product_details(id) on delete set null,
  product_detail_name text default '',
  path_label text default '',
  sku_seq int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz
);
create sequence if not exists seq_products;
create trigger trg_prod_code before insert on public.products
  for each row execute function public.set_seq_code('P','seq_products');

/* ============================ SKU ============================ */
create table public.skus (
  id uuid primary key default gen_random_uuid(),
  code text unique not null,
  product_id uuid not null references public.products(id) on delete restrict,
  product_name text default '',
  spec text default '',
  color text default '',
  release_year text default '',
  production_year text default '',
  purpose text default '',
  image_url text default '',
  product_main_image_url text default '',
  price numeric(14,2) not null default 0,
  qty int not null default 0,
  initial_qty int not null default 0,
  safety_stock int not null default 0,
  total_in int not null default 0,
  total_out int not null default 0,
  status text not null default 'in_stock' check (status in ('in_stock','low','out')),
  qr_generated boolean not null default true,
  complex_id uuid references public.complexes(id) on delete set null,
  complex_name text default '',
  category_id uuid references public.categories(id) on delete set null,
  product_code_id uuid references public.product_codes(id) on delete set null,
  product_detail_id uuid references public.product_details(id) on delete set null,
  path_label text default '',
  -- 위치
  storage_location_id uuid references public.storage_locations(id) on delete set null,
  storage_location_code text default '',
  zone_id uuid references public.zones(id) on delete set null,
  zone_name text default '',
  sub_zone_id uuid references public.sub_zones(id) on delete set null,
  sub_zone_name text default '',
  location_label text default '',
  location_verified_at timestamptz,
  location_verified_by text,
  last_moved_by text,
  last_moved_at timestamptz,
  -- 연한관리
  lifecycle_enabled boolean not null default false,
  cycle_value int default 0,
  cycle_unit text default 'month',
  last_replaced_at timestamptz,
  next_replace_at timestamptz,
  replace_reason text default '',
  lifecycle_note text default '',
  last_replaced_by text,
  created_at timestamptz not null default now(),
  updated_at timestamptz
);
create index idx_skus_product on public.skus(product_id);
create index idx_skus_complex on public.skus(complex_id);
create index idx_skus_code on public.skus(code);
create index idx_skus_next_replace on public.skus(next_replace_at) where lifecycle_enabled;

-- SKU 코드 자동 (상품코드 기반, 상품별 순번)
create or replace function public.set_sku_code()
returns trigger language plpgsql as $$
declare v_code text; v_seq int;
begin
  if new.code is null or new.code = '' then
    update public.products set sku_seq = coalesce(sku_seq,0) + 1
      where id = new.product_id returning code, sku_seq into v_code, v_seq;
    if v_code is null then raise exception '상품을 찾을 수 없습니다.'; end if;
    new.code := v_code || '-' || lpad(v_seq::text, 3, '0');
  end if;
  return new;
end $$;
create trigger trg_sku_code before insert on public.skus
  for each row execute function public.set_sku_code();

/* ============================ 원장 / 이력 / 집계 ============================ */
create table public.stock_movements (
  id uuid primary key default gen_random_uuid(),
  sku_id uuid not null references public.skus(id) on delete cascade,
  sku_code text,
  product_name text,
  type text not null check (type in ('in','out','adjust','audit')),
  qty int not null,
  delta int not null,
  before int not null,
  after int not null,
  complex_id uuid,
  complex_name text,
  path_label text,
  memo text default '',
  reason text default '',
  by_user_id uuid,
  by_name text,
  at timestamptz not null default now()
);
create index idx_movements_sku_at on public.stock_movements(sku_id, at desc);
create index idx_movements_at on public.stock_movements(at desc);

create table public.lifecycle_logs (
  id uuid primary key default gen_random_uuid(),
  sku_id uuid not null references public.skus(id) on delete cascade,
  sku_code text,
  product_name text,
  replaced_at timestamptz not null,
  next_replace_at timestamptz,
  reason text default '',
  path_label text,
  complex_name text,
  by_user_id uuid,
  by_name text,
  at timestamptz not null default now()
);
create index idx_lifecycle_logs_sku_at on public.lifecycle_logs(sku_id, at desc);

create table public.daily_stats (
  date date primary key,
  move_count int not null default 0,
  in_count int not null default 0,
  out_count int not null default 0,
  adjust_count int not null default 0,
  audit_count int not null default 0,
  in_qty int not null default 0,
  out_qty int not null default 0,
  updated_at timestamptz default now()
);

/* ============================ updated_at 트리거 ============================ */
create trigger t_u_complexes before update on public.complexes for each row execute function public.set_updated_at();
create trigger t_u_categories before update on public.categories for each row execute function public.set_updated_at();
create trigger t_u_product_codes before update on public.product_codes for each row execute function public.set_updated_at();
create trigger t_u_product_details before update on public.product_details for each row execute function public.set_updated_at();
create trigger t_u_zones before update on public.zones for each row execute function public.set_updated_at();
create trigger t_u_sub_zones before update on public.sub_zones for each row execute function public.set_updated_at();
create trigger t_u_storage_locations before update on public.storage_locations for each row execute function public.set_updated_at();
create trigger t_u_products before update on public.products for each row execute function public.set_updated_at();
create trigger t_u_skus before update on public.skus for each row execute function public.set_updated_at();


-- ---- 20260616090002_functions ----
-- 엠파크 WMS — 재고/연한 트랜잭션 함수 (RPC)
-- SECURITY DEFINER: 로그인 사용자가 재고/교체를 원자적으로 처리(RLS 우회하되 내부에서 권한 체크)

/* ===================== 입고/출고/조정/실사 ===================== */
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

/* ===================== 연한 교체 처리 ===================== */
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
  select display_name into v_actor from public.profiles where id = v_uid;

  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  if s.lifecycle_enabled then
    v_next := case lower(coalesce(s.cycle_unit,'month'))
      when 'day'  then v_now + (coalesce(s.cycle_value,0) || ' days')::interval
      when 'year' then v_now + (coalesce(s.cycle_value,0) || ' years')::interval
      else             v_now + (coalesce(s.cycle_value,0) || ' months')::interval
    end;
  else
    v_next := null;
  end if;

  update public.skus set last_replaced_at = v_now, next_replace_at = v_next, last_replaced_by = v_actor
   where id = p_sku_id;

  insert into public.lifecycle_logs
    (sku_id, sku_code, product_name, replaced_at, next_replace_at, reason, path_label, complex_name, by_user_id, by_name)
  values
    (p_sku_id, s.code, s.product_name, v_now, v_next,
     coalesce(nullif(p_reason,''), s.replace_reason, ''), s.path_label, s.complex_name, v_uid, v_actor);

  return json_build_object('replacedAt', v_now, 'nextReplaceAt', v_next);
end $$;


-- ---- 20260616090003_rls ----
-- 엠파크 WMS — RLS(행수준 보안) / 권한 / Storage
-- 원칙: 조회는 로그인 사용자, 관리(쓰기)는 admin. 재고/교체는 SECURITY DEFINER 함수로만.

/* ============================ 권한(GRANT) ============================ */
grant usage on schema public to authenticated, anon;
grant select, insert, update, delete on all tables in schema public to authenticated;
grant usage, select on all sequences in schema public to authenticated;
alter default privileges in schema public grant select, insert, update, delete on tables to authenticated;
alter default privileges in schema public grant usage, select on sequences to authenticated;

grant execute on function public.apply_stock(uuid, text, numeric, text, text) to authenticated;
grant execute on function public.replace_lifecycle(uuid, text) to authenticated;
grant execute on function public.is_admin() to authenticated, anon;

/* ============================ RLS 활성화 ============================ */
alter table public.profiles enable row level security;
alter table public.complexes enable row level security;
alter table public.categories enable row level security;
alter table public.product_codes enable row level security;
alter table public.product_details enable row level security;
alter table public.zones enable row level security;
alter table public.sub_zones enable row level security;
alter table public.storage_locations enable row level security;
alter table public.products enable row level security;
alter table public.skus enable row level security;
alter table public.stock_movements enable row level security;
alter table public.lifecycle_logs enable row level security;
alter table public.daily_stats enable row level security;

/* ---------- 프로필 ---------- */
create policy "profiles self/admin read" on public.profiles for select to authenticated
  using (id = auth.uid() or public.is_admin());
create policy "profiles admin update" on public.profiles for update to authenticated
  using (public.is_admin()) with check (public.is_admin());
create policy "profiles admin delete" on public.profiles for delete to authenticated
  using (public.is_admin());

/* ---------- 기준정보/위치/상품: 조회=로그인, 쓰기=admin ---------- */
do $$
declare t text;
begin
  foreach t in array array[
    'complexes','categories','product_codes','product_details',
    'zones','sub_zones','storage_locations','products','skus'
  ] loop
    execute format('create policy "%1$s read" on public.%1$I for select to authenticated using (true);', t);
    execute format('create policy "%1$s admin insert" on public.%1$I for insert to authenticated with check (public.is_admin());', t);
    execute format('create policy "%1$s admin update" on public.%1$I for update to authenticated using (public.is_admin()) with check (public.is_admin());', t);
    execute format('create policy "%1$s admin delete" on public.%1$I for delete to authenticated using (public.is_admin());', t);
  end loop;
end $$;

/* ---------- 원장/이력/집계: 조회만(쓰기는 SECURITY DEFINER 함수) ---------- */
create policy "movements read" on public.stock_movements for select to authenticated using (true);
create policy "lifecycle_logs read" on public.lifecycle_logs for select to authenticated using (true);
create policy "daily_stats read" on public.daily_stats for select to authenticated using (true);

/* ============================ Storage (이미지) ============================ */
insert into storage.buckets (id, name, public) values ('images', 'images', true)
  on conflict (id) do nothing;

create policy "images public read" on storage.objects for select
  using (bucket_id = 'images');
create policy "images admin insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'images' and public.is_admin());
create policy "images admin update" on storage.objects for update to authenticated
  using (bucket_id = 'images' and public.is_admin());
create policy "images admin delete" on storage.objects for delete to authenticated
  using (bucket_id = 'images' and public.is_admin());


-- ---- 20260616090004_location_logs ----
-- 엠파크 WMS — 보관위치 변경 이력
-- 재고조정에서 SKU 보관위치를 바꾸면 이력으로 저장(누가/언제/이전→이후)

create table if not exists public.location_logs (
  id uuid primary key default gen_random_uuid(),
  sku_id uuid not null references public.skus(id) on delete cascade,
  sku_code text,
  product_name text,
  from_label text,
  to_label text,
  storage_location_id uuid,
  storage_location_code text,
  location_label text,
  complex_name text,
  by_user_id uuid,
  by_name text,
  at timestamptz not null default now()
);
create index if not exists idx_location_logs_sku_at on public.location_logs(sku_id, at desc);

alter table public.location_logs enable row level security;
grant select, insert, update, delete on public.location_logs to authenticated;
drop policy if exists "location_logs read" on public.location_logs;
create policy "location_logs read" on public.location_logs for select to authenticated using (true);

-- 보관위치 설정 + 이력 기록 (변경이 있을 때만 로그). 위치 변경은 admin 만.
create or replace function public.set_location(
  p_sku_id uuid,
  p_storage_location_id uuid default null,
  p_storage_location_code text default '',
  p_zone_id uuid default null,
  p_zone_name text default '',
  p_sub_zone_id uuid default null,
  p_sub_zone_name text default '',
  p_location_label text default ''
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
  select display_name into v_actor from public.profiles where id = v_uid;

  select * into s from public.skus where id = p_sku_id for update;
  if not found then raise exception 'SKU를 찾을 수 없습니다.'; end if;

  v_changed := coalesce(s.storage_location_id::text,'') is distinct from coalesce(p_storage_location_id::text,'')
            or coalesce(s.location_label,'') is distinct from coalesce(p_location_label,'');

  update public.skus set
    storage_location_id = p_storage_location_id,
    storage_location_code = coalesce(p_storage_location_code,''),
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
  end if;

  return json_build_object('ok', true, 'changed', v_changed);
end $$;

grant execute on function public.set_location(uuid, uuid, text, uuid, text, uuid, text, text) to authenticated;


-- ---- 20260616090005_audit ----
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


-- ---- 20260616090006_initial_movement ----
-- 엠파크 WMS — SKU 최초 등록 수량을 입출고 이력에 기록
-- SKU 생성 시 초기 재고가 있으면 '입고(최초 등록)' 원장 1건 자동 생성

create or replace function public.sku_initial_movement()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  v_uid uuid := auth.uid();
  v_name text;
begin
  if coalesce(new.qty, 0) > 0 then
    if v_uid is not null then select display_name into v_name from public.profiles where id = v_uid; end if;
    insert into public.stock_movements
      (sku_id, sku_code, product_name, type, qty, delta, before, after,
       complex_id, complex_name, path_label, memo, reason, by_user_id, by_name)
    values
      (new.id, new.code, new.product_name, 'in', new.qty, new.qty, 0, new.qty,
       new.complex_id, new.complex_name, new.path_label, '', '최초 등록', v_uid, v_name);
    -- 입고누계 동기화 (audit 트리거 중복기록 방지)
    perform set_config('audit.skip', '1', true);
    update public.skus set total_in = new.qty where id = new.id;
  end if;
  return new;
end $$;

drop trigger if exists trg_sku_initial on public.skus;
create trigger trg_sku_initial after insert on public.skus
  for each row execute function public.sku_initial_movement();

-- 기존 SKU 백필: 초기수량이 있는데 원장이 전혀 없는 SKU 에 '최초 등록' 이력 생성
insert into public.stock_movements
  (sku_id, sku_code, product_name, type, qty, delta, before, after, complex_id, complex_name, path_label, reason)
select s.id, s.code, s.product_name, 'in', coalesce(s.initial_qty,0), coalesce(s.initial_qty,0), 0, coalesce(s.initial_qty,0),
       s.complex_id, s.complex_name, s.path_label, '최초 등록'
from public.skus s
where coalesce(s.initial_qty, 0) > 0
  and not exists (select 1 from public.stock_movements m where m.sku_id = s.id);


-- ---- 20260616090007_audit_name ----
-- 엠파크 WMS — 감사로그에 상품명/SKU명(name) 컬럼 추가
alter table public.audit_logs add column if not exists name text default '';

-- 트리거: name = 상품명(skus.product_name) 또는 이름(name)
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
  insert into public.audit_logs(module, table_name, action, row_id, label, name, by_user_id, by_name)
  values (tg_argv[0], tg_table_name, v_action,
          (v_row->>'id')::uuid,
          coalesce(v_row->>'code', v_row->>'name', ''),
          coalesce(v_row->>'product_name', v_row->>'name', ''),
          v_uid, v_name);
  return coalesce(new, old);
end $$;

-- 스톡 RPC용 직접기록: 해당 SKU의 상품명을 조회해 name 채움 (시그니처 유지)
create or replace function public.log_audit(p_module text, p_action text, p_row_id uuid, p_label text, p_uid uuid, p_name text)
returns void language plpgsql security definer set search_path = public as $$
declare v_item text;
begin
  select product_name into v_item from public.skus where id = p_row_id;
  insert into public.audit_logs(module, table_name, action, row_id, label, name, by_user_id, by_name)
  values (p_module, 'skus', p_action, p_row_id, p_label, coalesce(v_item,''), p_uid, p_name);
end $$;

-- 기존 로그 백필 (상품명/이름)
update public.audit_logs a set name = s.product_name
  from public.skus s where a.row_id = s.id and a.table_name = 'skus' and coalesce(a.name,'') = '';
update public.audit_logs a set name = p.name
  from public.products p where a.row_id = p.id and a.table_name = 'products' and coalesce(a.name,'') = '';
update public.audit_logs a set name = a.label
  where coalesce(a.name,'') = '' and a.table_name in ('complexes','categories','product_codes','product_details','zones','sub_zones','storage_locations');


-- ---- 20260616090008_sku_dimensions ----
-- 엠파크 WMS — SKU 치수(가로/세로/높이/깊이, cm) 컬럼 추가
alter table public.skus
  add column if not exists dim_w numeric,
  add column if not exists dim_l numeric,
  add column if not exists dim_h numeric,
  add column if not exists dim_d numeric;

