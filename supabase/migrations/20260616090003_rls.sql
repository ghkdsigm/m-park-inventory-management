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
