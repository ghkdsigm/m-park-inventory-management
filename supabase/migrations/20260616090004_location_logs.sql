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
