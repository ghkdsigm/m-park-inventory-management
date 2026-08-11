#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
SKU 단지 귀속 + 공유 SKU 분리.
  · 모든 SKU 에 complex 설정(재고행의 단지 기준)
  · 여러 단지에 걸친 SKU 는 단지별 별도 SKU 로 분리(새 코드 = product.code-{seq}), 해당 단지 재고·이력 재귀속
  · 안전재고 = 파일의 (단지+품목+규격)별 실제값 (per-단지)
DRY-RUN 기본. --apply 로 로컬 반영.
"""
import os, sys, re, unicodedata, uuid
from collections import defaultdict
import openpyxl, pymysql

APPLY = "--apply" in sys.argv
SAFE = r"C:\Users\hwangseunghyun\Downloads\00. 시설자재 기준 테이블_20260805.xlsx"
DB = dict(host="127.0.0.1", port=3307, user="mpark", password="mpark", database="mpark_wms", charset="utf8mb4")
CXID = {"랜드": "b71d8335-72ae-4238-ae57-1abc55889886",
        "타워": "39feef02-9b14-434b-86b6-51bee5dee7a8",
        "허브": "a088b269-aaaa-4ba7-b1ca-bc237849db1a"}

def base_spec(s): return re.sub(r"\s*-\s*\d+ea\(1세트수량\)\s*$", "", str(s or "")).strip()
def norm(s): return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(s or "")).casefold())


def load_safety():
    wb = openpyxl.load_workbook(SAFE, read_only=True, data_only=True); ws = wb["기준 Table"]
    m = {}
    for r in ws.iter_rows(min_row=9, values_only=True):
        no, cx, item, spec, qty = r[1], r[4], r[6], r[7], r[8]
        if no is None: continue
        if isinstance(qty, (int, float)):
            m[(norm(cx), norm(item), norm(base_spec(spec)))] = int(qty)
    wb.close()
    return m


def main():
    safety = load_safety()
    conn = pymysql.connect(**DB, autocommit=False); cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM skus WHERE complex_id IS NOT NULL")
    if cur.fetchone()[0] > 0 and APPLY:
        print("[중단] 이미 complex 설정된 SKU 있음. 재실행 방지."); conn.close(); return

    cur.execute("SELECT id, product_id, product_name, spec FROM skus")
    skus = {r[0]: dict(pid=r[1], pname=r[2], spec=r[3]) for r in cur.fetchall()}
    cur.execute("SELECT id, sku_id, complex_name FROM stock")
    stock_by_sku = defaultdict(list)
    for sid, sku_id, cxn in cur.fetchall():
        stock_by_sku[sku_id].append((sid, cxn))

    single, multi = 0, 0
    new_skus = 0
    plan = []  # for report
    for sku_id, sk in skus.items():
        cxs = sorted(set(cxn for _, cxn in stock_by_sku.get(sku_id, []) if cxn))
        if not cxs:
            continue
        keep = cxs[0]
        saf0 = safety.get((norm(keep), norm(sk['pname']), norm(base_spec(sk['spec']))), 0)
        if len(cxs) == 1:
            single += 1
        else:
            multi += 1
        plan.append((sku_id, sk['pname'], sk['spec'], keep, cxs[1:], saf0))
        if not APPLY:
            continue
        # 원본 SKU: keep 단지로 설정 + 안전재고
        cur.execute("UPDATE skus SET complex_id=%s, complex_name=%s, safety_stock=%s WHERE id=%s",
                    (CXID.get(keep), keep, saf0, sku_id))
        # 나머지 단지 → 새 SKU 분리
        for extra in cxs[1:]:
            cur.execute("SELECT code, sku_seq FROM products WHERE id=%s FOR UPDATE", (sk['pid'],))
            pcode, seq = cur.fetchone(); seq = (seq or 0) + 1
            cur.execute("UPDATE products SET sku_seq=%s WHERE id=%s", (seq, sk['pid']))
            ncode = "%s-%03d" % (pcode, seq)
            nsid = str(uuid.uuid4())
            saf = safety.get((norm(extra), norm(sk['pname']), norm(base_spec(sk['spec']))), 0)
            # 새 SKU: 원본 속성 복제(주요 컬럼) + 단지/코드/안전재고
            cur.execute("""INSERT INTO skus (id, code, product_id, complex_id, complex_name, product_name, spec,
                   color, release_year, production_year, purpose, image_url, product_main_image_url,
                   price, safety_stock, qr_generated, category_id, product_code_id, product_detail_id, path_label, created_at)
                SELECT %s, %s, product_id, %s, %s, product_name, spec, color, release_year, production_year, purpose,
                   image_url, product_main_image_url, price, %s, 1, category_id, product_code_id, product_detail_id, path_label, NOW(6)
                FROM skus WHERE id=%s""", (nsid, ncode, CXID.get(extra), extra, saf, sku_id))
            # extra 단지 재고·이력 재귀속
            cur.execute("UPDATE stock SET sku_id=%s WHERE sku_id=%s AND complex_name=%s", (nsid, sku_id, extra))
            cur.execute("UPDATE stock_movements SET sku_id=%s, sku_code=%s WHERE sku_id=%s AND complex_name=%s",
                        (nsid, ncode, sku_id, extra))
            new_skus += 1

    print(f"단일단지 SKU {single} · 다단지 SKU {multi} → 분리로 새 SKU {sum(len(p[4]) for p in plan)}개 예정")
    print("\n[분리 대상]")
    for sku_id, pn, sp, keep, extras, saf in plan:
        if extras:
            print(f"   {pn}/{sp}: 유지={keep}(safety {saf}) → 분리 {extras}")

    if not APPLY:
        print("\n(DRY-RUN) 변경 없음. --apply 로 반영")
        conn.close(); return

    # 상태 재계산(per-단지 안전재고 기준)
    cur.execute("""UPDATE stock st JOIN skus s ON s.id=st.sku_id
        SET st.status = CASE WHEN st.qty<=0 THEN 'out' WHEN st.qty<=s.safety_stock THEN 'low' ELSE 'in_stock' END""")
    conn.commit()
    print(f"\n[COMMIT] 새 SKU {new_skus}개 생성, 상태 재계산 완료")
    conn.close()


if __name__ == "__main__":
    main()
