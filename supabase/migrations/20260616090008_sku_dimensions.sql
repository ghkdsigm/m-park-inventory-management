-- 엠파크 WMS — SKU 치수(가로/세로/높이/깊이, cm) 컬럼 추가
alter table public.skus
  add column if not exists dim_w numeric,
  add column if not exists dim_l numeric,
  add column if not exists dim_h numeric,
  add column if not exists dim_d numeric;
