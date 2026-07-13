-- MySQL 시드: 허브 단지 + 구역 + 상세구역 + 보관위치
-- 여러 번 실행해도 중복 없음 (INSERT IGNORE)
SET NAMES utf8mb4;

-- 1) 단지
SET @cx_id = UUID();
INSERT IGNORE INTO complexes(id, code, name, description)
VALUES (@cx_id, 'HUB', '허브', '허브 단지');
-- 이미 있으면 기존 id 사용
SELECT id INTO @cx_id FROM complexes WHERE code = 'HUB' LIMIT 1;

-- 2) 구역 (zones)
INSERT INTO zones(id, name, complex_id, complex_name)
SELECT UUID(), z.name, @cx_id, '허브'
FROM (SELECT '관리동' AS name UNION ALL SELECT '고객센터' UNION ALL SELECT '전시장A동'
      UNION ALL SELECT '전시장B동' UNION ALL SELECT '전시장C동' UNION ALL SELECT '주차타워'
      UNION ALL SELECT '지하주차장' UNION ALL SELECT '정비센터' UNION ALL SELECT '세차장'
      UNION ALL SELECT '전기실' UNION ALL SELECT '기계실' UNION ALL SELECT '중앙창고'
      UNION ALL SELECT '야외광장' UNION ALL SELECT '출입구' UNION ALL SELECT '공용화장실') z
WHERE NOT EXISTS (SELECT 1 FROM zones WHERE zones.name = z.name AND zones.complex_id = @cx_id);

-- 3) 상세구역 (sub_zones)
INSERT INTO sub_zones(id, name, zone_id, zone_name, complex_id, complex_name)
SELECT UUID(), t.sub, zn.id, zn.name, @cx_id, '허브'
FROM (
  SELECT '관리동' AS zone_name, '시설팀' AS sub UNION ALL SELECT '관리동','운영팀' UNION ALL SELECT '관리동','총무팀'
  UNION ALL SELECT '관리동','전산실' UNION ALL SELECT '관리동','문서보관실' UNION ALL SELECT '관리동','휴게실'
  UNION ALL SELECT '고객센터','안내데스크' UNION ALL SELECT '고객센터','상담실' UNION ALL SELECT '고객센터','대기실'
  UNION ALL SELECT '고객센터','문서보관실' UNION ALL SELECT '고객센터','창고'
  UNION ALL SELECT '전시장A동','1층' UNION ALL SELECT '전시장A동','2층' UNION ALL SELECT '전시장A동','3층'
  UNION ALL SELECT '전시장A동','전기실' UNION ALL SELECT '전시장A동','기계실' UNION ALL SELECT '전시장A동','창고'
  UNION ALL SELECT '전시장B동','1층' UNION ALL SELECT '전시장B동','2층'
  UNION ALL SELECT '전시장B동','전기실' UNION ALL SELECT '전시장B동','기계실' UNION ALL SELECT '전시장B동','창고'
  UNION ALL SELECT '주차타워','1층' UNION ALL SELECT '주차타워','2층' UNION ALL SELECT '주차타워','3층'
  UNION ALL SELECT '주차타워','4층' UNION ALL SELECT '주차타워','5층' UNION ALL SELECT '주차타워','전기실'
  UNION ALL SELECT '주차타워','기계실' UNION ALL SELECT '주차타워','관리실'
  UNION ALL SELECT '지하주차장','B1' UNION ALL SELECT '지하주차장','B2' UNION ALL SELECT '지하주차장','B3'
  UNION ALL SELECT '지하주차장','전기실' UNION ALL SELECT '지하주차장','펌프실' UNION ALL SELECT '지하주차장','창고'
  UNION ALL SELECT '정비센터','정비1구역' UNION ALL SELECT '정비센터','정비2구역' UNION ALL SELECT '정비센터','정비3구역'
  UNION ALL SELECT '정비센터','부품창고' UNION ALL SELECT '정비센터','공구실' UNION ALL SELECT '정비센터','오일창고'
  UNION ALL SELECT '세차장','자동세차구역' UNION ALL SELECT '세차장','수동세차구역' UNION ALL SELECT '세차장','약품보관실'
  UNION ALL SELECT '세차장','장비보관실' UNION ALL SELECT '세차장','창고'
  UNION ALL SELECT '중앙창고','전기자재창고' UNION ALL SELECT '중앙창고','수도자재창고' UNION ALL SELECT '중앙창고','소방자재창고'
  UNION ALL SELECT '중앙창고','청소용품창고' UNION ALL SELECT '중앙창고','안전용품창고' UNION ALL SELECT '중앙창고','공구창고'
  UNION ALL SELECT '중앙창고','사무용품창고' UNION ALL SELECT '중앙창고','소모품창고'
) t
JOIN zones zn ON zn.name = t.zone_name AND zn.complex_id = @cx_id
WHERE NOT EXISTS (SELECT 1 FROM sub_zones WHERE sub_zones.name = t.sub AND sub_zones.zone_id = zn.id);

-- 4) 보관위치 (storage_locations): 중앙창고 > 전기자재창고 하위
SELECT id INTO @z_central FROM zones WHERE name = '중앙창고' AND complex_id = @cx_id LIMIT 1;
SELECT id INTO @sz_elec FROM sub_zones WHERE name = '전기자재창고' AND zone_id = @z_central LIMIT 1;

-- 앱의 CodeGenerator 와 동일한 형식으로 발급: "LOC-000001" (prefix + '-' + %06d).
-- 이미 있는 코드에서 숫자만 뽑아 최댓값을 구한 뒤 이어서 증가시킨다(하이픈 유무 무관).
SET @loc_seq = (SELECT COALESCE(MAX(CAST(REGEXP_REPLACE(code, '[^0-9]', '') AS UNSIGNED)), 0)
                FROM storage_locations WHERE code LIKE 'LOC%');

INSERT INTO storage_locations(id, code, name, complex_id, complex_name, zone_id, zone_name, sub_zone_id, sub_zone_name, location_label)
SELECT UUID(),
       CONCAT('LOC-', LPAD(@loc_seq := @loc_seq + 1, 6, '0')),
       loc.name, @cx_id, '허브', @z_central, '중앙창고', @sz_elec, '전기자재창고',
       CONCAT('중앙창고 > 전기자재창고 > ', loc.name)
FROM (SELECT 'A선반' AS name UNION ALL SELECT 'B선반' UNION ALL SELECT 'C선반' UNION ALL SELECT 'D선반'
      UNION ALL SELECT '상부랙' UNION ALL SELECT '하부랙' UNION ALL SELECT '캐비닛01' UNION ALL SELECT '캐비닛02'
      UNION ALL SELECT '입고대기구역' UNION ALL SELECT '불용품보관구역') loc
WHERE @sz_elec IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM storage_locations WHERE storage_locations.name = loc.name AND storage_locations.sub_zone_id = @sz_elec);

-- seq_counters 를 실제 최대 코드번호에 맞춘다(개수 COUNT 가 아니라 최댓값).
-- 그래야 이후 앱(UI)에서 보관위치를 추가할 때 다음 번호를 충돌 없이 이어서 발급한다.
-- 기존 값보다 낮추지 않도록 GREATEST 로 보호.
UPDATE seq_counters
SET val = GREATEST(val, (SELECT COALESCE(MAX(CAST(REGEXP_REPLACE(code, '[^0-9]', '') AS UNSIGNED)), 0)
                         FROM storage_locations WHERE code LIKE 'LOC%'))
WHERE name = 'storage_locations';

SELECT '시드 완료' AS result;
