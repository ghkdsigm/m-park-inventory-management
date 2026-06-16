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
