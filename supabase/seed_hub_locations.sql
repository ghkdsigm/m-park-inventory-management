-- 허브 단지: 위치코드(구역/상세구역) + 보관위치(중앙창고>전기자재창고) 시드
-- SQL Editor 에 통째로 붙여넣고 Run. (여러 번 실행해도 중복 없음)

do $$
declare
  cn text := '허브';
  v_cx uuid; v_zid uuid; v_sid uuid; z text; rec record;
begin
  perform set_config('audit.skip', '1', true);
  select id into v_cx from public.complexes where name = cn limit 1;
  if v_cx is null then raise exception '단지 "%" 를 찾을 수 없습니다.', cn; end if;

  -- 1) 구역 (zones)
  foreach z in array array[
    '관리동','고객센터','전시장A동','전시장B동','전시장C동','주차타워','지하주차장',
    '정비센터','세차장','전기실','기계실','중앙창고','야외광장','출입구','공용화장실'
  ] loop
    if not exists (select 1 from public.zones where name = z and complex_id = v_cx) then
      insert into public.zones(name, complex_id, complex_name) values (z, v_cx, cn);
    end if;
  end loop;

  -- 2) 상세구역 (sub_zones)
  for rec in select zone, sub from (values
    ('관리동','시설팀'),('관리동','운영팀'),('관리동','총무팀'),('관리동','전산실'),('관리동','문서보관실'),('관리동','휴게실'),
    ('고객센터','안내데스크'),('고객센터','상담실'),('고객센터','대기실'),('고객센터','문서보관실'),('고객센터','창고'),
    ('전시장A동','1층'),('전시장A동','2층'),('전시장A동','3층'),('전시장A동','전기실'),('전시장A동','기계실'),('전시장A동','창고'),
    ('전시장B동','1층'),('전시장B동','2층'),('전시장B동','전기실'),('전시장B동','기계실'),('전시장B동','창고'),
    ('주차타워','1층'),('주차타워','2층'),('주차타워','3층'),('주차타워','4층'),('주차타워','5층'),('주차타워','전기실'),('주차타워','기계실'),('주차타워','관리실'),
    ('지하주차장','B1'),('지하주차장','B2'),('지하주차장','B3'),('지하주차장','전기실'),('지하주차장','펌프실'),('지하주차장','창고'),
    ('정비센터','정비1구역'),('정비센터','정비2구역'),('정비센터','정비3구역'),('정비센터','부품창고'),('정비센터','공구실'),('정비센터','오일창고'),
    ('세차장','자동세차구역'),('세차장','수동세차구역'),('세차장','약품보관실'),('세차장','장비보관실'),('세차장','창고'),
    ('중앙창고','전기자재창고'),('중앙창고','수도자재창고'),('중앙창고','소방자재창고'),('중앙창고','청소용품창고'),
    ('중앙창고','안전용품창고'),('중앙창고','공구창고'),('중앙창고','사무용품창고'),('중앙창고','소모품창고')
  ) as t(zone, sub) loop
    select id into v_zid from public.zones where name = rec.zone and complex_id = v_cx limit 1;
    if v_zid is not null and not exists (select 1 from public.sub_zones where name = rec.sub and zone_id = v_zid) then
      insert into public.sub_zones(name, zone_id, zone_name, complex_id, complex_name)
        values (rec.sub, v_zid, rec.zone, v_cx, cn);
    end if;
  end loop;

  -- 3) 보관위치 (storage_locations): 허브 > 중앙창고 > 전기자재창고
  select id into v_zid from public.zones where name = '중앙창고' and complex_id = v_cx limit 1;
  select id into v_sid from public.sub_zones where name = '전기자재창고' and zone_id = v_zid limit 1;
  if v_sid is not null then
    foreach z in array array[
      'A선반','B선반','C선반','D선반','상부랙','하부랙','캐비닛01','캐비닛02','입고대기구역','불용품보관구역'
    ] loop
      if not exists (select 1 from public.storage_locations where name = z and sub_zone_id = v_sid) then
        insert into public.storage_locations(name, complex_id, complex_name, zone_id, zone_name, sub_zone_id, sub_zone_name, location_label)
          values (z, v_cx, cn, v_zid, '중앙창고', v_sid, '전기자재창고', '중앙창고 > 전기자재창고 > ' || z);
      end if;
    end loop;
  end if;

  raise notice '완료: 구역 15 / 상세구역 53 / 보관위치 10(중앙창고>전기자재창고)';
end $$;
