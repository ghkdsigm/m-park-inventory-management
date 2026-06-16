-- 엠파크 WMS — SKU 서버측 페이징/필터/정렬/집계 RPC (#1 풀로드 제거)
-- 클라이언트가 전 SKU를 받아 JS로 필터/페이징하던 것을 DB 한 번 호출로 대체.
-- 반환: { total, totalQty, lowCount, outCount, rows:[...] }  (rows = 해당 페이지만)

/* ---------- 필터 컬럼 인덱스(동등조건 가속) ---------- */
create index if not exists idx_skus_category on public.skus(category_id);
create index if not exists idx_skus_product_code on public.skus(product_code_id);
create index if not exists idx_skus_product_detail on public.skus(product_detail_id);
create index if not exists idx_skus_status on public.skus(status);
create index if not exists idx_skus_created on public.skus(created_at desc);

/* ---------- 페이징 RPC ---------- */
create or replace function public.skus_page(
  p_complex uuid default null,
  p_category uuid default null,
  p_product_code uuid default null,
  p_product_detail uuid default null,
  p_status text default null,
  p_search text default null,
  p_lifecycle_only boolean default false,
  p_sort text default 'recent',
  p_limit int default 10,
  p_offset int default 0
) returns json
language sql stable security definer set search_path = public as $$
  with f as (
    select * from public.skus s
    where (p_complex is null or s.complex_id = p_complex)
      and (p_category is null or s.category_id = p_category)
      and (p_product_code is null or s.product_code_id = p_product_code)
      and (p_product_detail is null or s.product_detail_id = p_product_detail)
      and (p_status is null or p_status = '' or s.status = p_status)
      and (not p_lifecycle_only or s.lifecycle_enabled)
      and (
        p_search is null or p_search = ''
        or s.code ilike '%' || p_search || '%'
        or s.product_name ilike '%' || p_search || '%'
        or s.spec ilike '%' || p_search || '%'
        or s.path_label ilike '%' || p_search || '%'
      )
  ),
  agg as (
    select count(*) as total,
           coalesce(sum(qty), 0) as total_qty,
           count(*) filter (where status = 'low') as low_count,
           count(*) filter (where status = 'out') as out_count
    from f
  ),
  page as (
    select * from f
    order by
      case when p_sort = 'qtyAsc'      then qty end asc  nulls last,
      case when p_sort = 'qtyDesc'     then qty end desc nulls last,
      case when p_sort = 'outDesc'     then total_out end desc nulls last,
      case when p_sort = 'inDesc'      then total_in end desc nulls last,
      case when p_sort = 'nextReplace' then next_replace_at end asc nulls last,
      case when p_sort = 'code'        then code end asc,
      created_at desc
    limit greatest(p_limit, 0) offset greatest(p_offset, 0)
  )
  select json_build_object(
    'total',    (select total from agg),
    'totalQty', (select total_qty from agg),
    'lowCount', (select low_count from agg),
    'outCount', (select out_count from agg),
    'rows',     coalesce((select json_agg(page.*) from page), '[]'::json)
  );
$$;

/* ---------- 단지별 묶기(요약) RPC: 단지별 SKU수/총재고/부족/품절 ---------- */
create or replace function public.skus_group_by_complex(
  p_complex uuid default null,
  p_category uuid default null,
  p_product_code uuid default null,
  p_product_detail uuid default null,
  p_status text default null,
  p_search text default null
) returns table(complex_name text, sku_count bigint, total_qty bigint, low_count bigint, out_count bigint)
language sql stable security definer set search_path = public as $$
  select coalesce(nullif(complex_name, ''), '미지정') as complex_name,
         count(*) as sku_count,
         coalesce(sum(qty), 0) as total_qty,
         count(*) filter (where status = 'low') as low_count,
         count(*) filter (where status = 'out') as out_count
  from public.skus s
  where (p_complex is null or s.complex_id = p_complex)
    and (p_category is null or s.category_id = p_category)
    and (p_product_code is null or s.product_code_id = p_product_code)
    and (p_product_detail is null or s.product_detail_id = p_product_detail)
    and (p_status is null or p_status = '' or s.status = p_status)
    and (
      p_search is null or p_search = ''
      or s.code ilike '%' || p_search || '%'
      or s.product_name ilike '%' || p_search || '%'
      or s.spec ilike '%' || p_search || '%'
      or s.path_label ilike '%' || p_search || '%'
    )
  group by 1
  order by total_qty desc;
$$;

grant execute on function public.skus_page(uuid, uuid, uuid, uuid, text, text, boolean, text, int, int) to authenticated;
grant execute on function public.skus_group_by_complex(uuid, uuid, uuid, uuid, text, text) to authenticated;
