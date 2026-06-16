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
