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
