#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
단지별 SKU 이미지 재매칭 (SKU 단지분리 이후 최종본).
  · S3 이미지명 = 마스터리스트 No. (품목/규격이 아니라 No 매칭)
  · SKU 단지귀속: (단지+품목+규격) → No → https://.../<No>.jpg
  · 각 SKU의 image_url 을 올바른 값으로 재설정(분리 시 잘못 복사된 것 제거 포함)
  · 값이 바뀌는 SKU만 UPDATE.
사용: python scripts/match_images_final.py [--prod] [--apply]
"""
import sys, re, unicodedata, urllib.request
from concurrent.futures import ThreadPoolExecutor
import openpyxl, pymysql

APPLY = "--apply" in sys.argv
PROD = "--prod" in sys.argv
BASE = "https://dwe-on-mpark.s3.ap-northeast-2.amazonaws.com/mpark/stock/image/product"
MASTER = r"C:\Users\hwangseunghyun\Downloads\00. 시설자재 기준 테이블_20260805.xlsx"
DB = (dict(host="hayabusa.proxy.rlwy.net", port=22649, user="root",
           password="SBNpsSCcANOmGcXuFHgQhiSvMgrrYpwL", database="railway", charset="utf8mb4")
      if PROD else
      dict(host="127.0.0.1", port=3307, user="mpark", password="mpark", database="mpark_wms", charset="utf8mb4"))

def bspec(s): return re.sub(r"\s*-\s*\d+ea\(1세트수량\)\s*$", "", str(s or "")).strip()
def norm(s): return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(s or "")).casefold())


def scan_images():
    def head(n):
        try:
            with urllib.request.urlopen(urllib.request.Request(f"{BASE}/{n}.jpg", method="HEAD"), timeout=10) as r:
                return (n, r.status)
        except Exception as e:
            return (n, getattr(e, "code", -1))
    with ThreadPoolExecutor(max_workers=40) as ex:
        res = list(ex.map(head, range(1, 411)))
    return set(n for n, c in res if c == 200)


def load_master(imaged):
    wb = openpyxl.load_workbook(MASTER, read_only=True, data_only=True); ws = wb["기준 Table"]
    k2no = {}
    for r in ws.iter_rows(min_row=9, values_only=True):
        no, cx, item, spec = r[1], r[4], r[6], r[7]
        if no is None: continue
        k = (norm(cx), norm(item), norm(bspec(spec)))
        if k not in k2no or (no in imaged and k2no[k] not in imaged):  # 이미지 있는 No 우선
            k2no[k] = no
    wb.close()
    return k2no


def main():
    imaged = scan_images()
    k2no = load_master(imaged)
    print(f"[cfg] 대상={'운영' if PROD else '로컬'} · S3이미지 {len(imaged)} · 마스터키 {len(k2no)}")
    conn = pymysql.connect(**DB, autocommit=False); cur = conn.cursor()
    cur.execute("SELECT id, complex_name, product_name, spec, image_url FROM skus")
    updates = []
    for sid, cx, pn, sp, img in cur.fetchall():
        no = k2no.get((norm(cx), norm(pn), norm(bspec(sp))))
        newurl = f"{BASE}/{no}.jpg" if (no in imaged) else ""
        if (img or "") != newurl:
            updates.append((newurl, sid, f"{cx}/{pn}/{sp}", img, newurl))
    set_n = sum(1 for u in updates if u[4])
    clr_n = sum(1 for u in updates if not u[4])
    print(f"변경 대상 {len(updates)}건 (설정/교정 {set_n} · 제거 {clr_n})")
    for _, _, label, old, new in updates[:30]:
        print(f"   {label}: {old or '(없음)'} → {new or '(제거)'}")
    if not APPLY:
        print("\n(DRY-RUN) --apply 로 반영"); conn.close(); return
    cur.executemany("UPDATE skus SET image_url=%s WHERE id=%s", [(u[0], u[1]) for u in updates])
    conn.commit()
    cur.execute("SELECT COUNT(*) FROM skus WHERE image_url<>''")
    print(f"\n[COMMIT] {len(updates)}건 변경 · 현재 image_url 보유 {cur.fetchone()[0]}")
    conn.close()


if __name__ == "__main__":
    main()
