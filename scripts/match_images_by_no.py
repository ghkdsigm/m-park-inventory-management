#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
S3 상품사진(파일명 = 마스터리스트 No) → SKU 매칭 → skus.image_url 주입.

매칭 방식:
  · 이미지 파일명 <No>.jpg 의 No → master_list_images.tsv 에서 (단지·품목·규격) 조회
  · 그 (단지·품목·규격) 을 DB SKU (complex via stock · product_name · spec) 와 정규화 매칭
  · 정규화 = NFC + casefold + 모든 공백 제거  ("V 벨트" == "V벨트")
  · image_url = https://dwe-on-mpark.s3.ap-northeast-2.amazonaws.com/mpark/stock/image/product/<No>.jpg

사용:
  python scripts/match_images_by_no.py           # 미리보기(DRY-RUN, DB 변경 없음)
  python scripts/match_images_by_no.py --apply    # 실제 반영
"""
import os, sys, re, csv, unicodedata
import pymysql

TSV = os.path.join(os.path.dirname(__file__), "master_list_images.tsv")
BASE_URL = "https://dwe-on-mpark.s3.ap-northeast-2.amazonaws.com/mpark/stock/image/product"
APPLY = "--apply" in sys.argv

DB = dict(host="127.0.0.1", port=3307, user="mpark", password="mpark", db="mpark_wms", charset="utf8mb4")


def norm(s):
    if s is None:
        return ""
    s = unicodedata.normalize("NFC", str(s)).casefold()
    return re.sub(r"\s+", "", s)  # 모든 공백 제거


def load_master():
    rows = []
    with open(TSV, encoding="utf-8") as f:
        r = csv.reader(f, delimiter="\t")
        header = next(r)
        for parts in r:
            if not parts or not parts[0].strip():
                continue
            parts += [""] * (5 - len(parts))  # 규격 없는 행 보정
            no, complex_, gubun, item, spec = parts[0], parts[1], parts[2], parts[3], parts[4]
            rows.append(dict(no=int(no), complex=complex_, gubun=gubun, item=item, spec=spec))
    return rows


def load_skus(cur):
    # SKU + 연결된 단지(복수 가능) 조회
    cur.execute("""
        SELECT s.id, s.product_name, s.spec, s.image_url,
               GROUP_CONCAT(DISTINCT cx.name) AS complexes
        FROM skus s
        LEFT JOIN stock st ON st.sku_id = s.id
        LEFT JOIN storage_locations sl ON sl.id = st.storage_location_id
        LEFT JOIN complexes cx ON cx.id = sl.complex_id
        GROUP BY s.id, s.product_name, s.spec, s.image_url
    """)
    idx = {}  # (ncomplex, nitem, nspec) -> [sku_id,...]
    skus = {}
    for sid, pname, spec, img, complexes in cur.fetchall():
        skus[sid] = dict(product_name=pname, spec=spec, image_url=img, complexes=complexes or "")
        for cx in (complexes or "").split(","):
            key = (norm(cx), norm(pname), norm(spec))
            idx.setdefault(key, []).append(sid)
    return skus, idx


def main():
    master = load_master()
    print(f"[master] 이미지 대상 행: {len(master)}개  (No {master[0]['no']}~{master[-1]['no']})")
    print(f"[mode] {'APPLY (DB 갱신)' if APPLY else 'DRY-RUN (미리보기)'}\n")

    conn = pymysql.connect(**DB, autocommit=False)
    cur = conn.cursor()
    skus, idx = load_skus(cur)
    print(f"[db] SKU {len(skus)}개 로드\n")

    matched, ambiguous, unmatched = [], [], []
    for m in master:
        key = (norm(m["complex"]), norm(m["item"]), norm(m["spec"]))
        hits = idx.get(key, [])
        url = f"{BASE_URL}/{m['no']}.jpg"
        if len(hits) == 1:
            matched.append((m, hits[0], url))
        elif len(hits) > 1:
            ambiguous.append((m, hits, url))
        else:
            unmatched.append(m)

    print(f"== 결과: 매칭 {len(matched)} · 모호(다중) {len(ambiguous)} · 미매칭 {len(unmatched)} ==\n")

    print("-- ✅ 매칭 (No → SKU) --")
    for m, sid, url in matched:
        s = skus[sid]
        print(f"  {m['no']:>3} {m['complex']}/{m['item']}/{m['spec']}  →  [{s['product_name']}/{s['spec']}] {sid[:8]}")

    if ambiguous:
        print("\n-- ⚠️ 모호(같은 키에 SKU 여러개) — 전부에 적용 예정 --")
        for m, hits, url in ambiguous:
            print(f"  {m['no']:>3} {m['complex']}/{m['item']}/{m['spec']}  →  {len(hits)}개 SKU")

    if unmatched:
        print("\n-- ❌ 미매칭 (이미지는 있으나 매칭 SKU 없음) --")
        for m in unmatched:
            print(f"  {m['no']:>3} {m['complex']}/{m['item']}/{m['spec']}")

    if not APPLY:
        print(f"\n(DRY-RUN) 변경 없음. 반영: python scripts/match_images_by_no.py --apply")
        conn.close()
        return

    updates = [(url, sid) for m, sid, url in matched]
    for m, hits, url in ambiguous:
        for sid in hits:
            updates.append((url, sid))
    cur.executemany("UPDATE skus SET image_url=%s WHERE id=%s", updates)
    conn.commit()
    conn.close()
    print(f"\n[apply] {len(updates)}개 SKU image_url 갱신 완료.")


if __name__ == "__main__":
    main()
