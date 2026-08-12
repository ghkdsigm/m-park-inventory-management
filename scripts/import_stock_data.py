#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
시설자재 재고 임포터: 입출고 이력 + 안전재고 + 삭제/품명변경.
DRY-RUN 기본(리포트만). --apply 로 로컬 DB 반영.

소스:
  MOVE = 21. 시설자재 관리 Master File.xlsx :: raw data
  SAFE = 00. 시설자재 기준 테이블_20260805.xlsx :: 기준 Table
매칭: (품목, base_spec)  base_spec = 규격에서 " - Nea(1세트수량)" 접미 제거
"""
import os, sys, re, unicodedata, datetime
from collections import defaultdict, Counter
import openpyxl, pymysql

APPLY = "--apply" in sys.argv
PROD = "--prod" in sys.argv
MOVE = r"C:\Users\hwangseunghyun\Downloads\21. 시설자재 관리 Master File.xlsx"
SAFE = r"C:\Users\hwangseunghyun\Downloads\00. 시설자재 기준 테이블_20260805.xlsx"
DB = (dict(host="hayabusa.proxy.rlwy.net", port=22649, user="root",
           password="SBNpsSCcANOmGcXuFHgQhiSvMgrrYpwL", database="railway", charset="utf8mb4")
      if PROD else
      dict(host="127.0.0.1", port=3307, user="mpark", password="mpark", database="mpark_wms", charset="utf8mb4"))

DELETE_KEYS = {("검전기(특고압)", "AC80-30KV"), ("페인트풋", "1 1/2''"), ("페인트풋", "1/2''")}
RENAME = ("LED 간판 안정기", "GW-03(RS485)", "변압기 온도 콘트롤러")  # (품목,규격) -> 새품목
# 실재고 있는 신규 3개만 생성
NEW_OK = {("전기테이프", "20ROLL 1BOX"), ("힌지", "KING 8500"), ("수중펌프", "1/6HP")}


def base_spec(s):
    return re.sub(r"\s*-\s*\d+ea\(1세트수량\)\s*$", "", str(s or "")).strip()
def norm(s):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(s or "")).casefold())
def K(item, spec):
    return (norm(item), norm(base_spec(spec)))
def dkey(item, spec):
    return (str(item).strip(), base_spec(spec))


def to_date(d):
    if isinstance(d, datetime.datetime): return d.date()
    if isinstance(d, datetime.date): return d
    return None


def read_moves():
    wb = openpyxl.load_workbook(MOVE, read_only=True, data_only=True); ws = wb["raw data"]
    out = []
    for r in ws.iter_rows(min_row=3, values_only=True):
        cx, gb, date, qty, io, item, spec = r[1], r[2], r[4], r[5], r[7], r[12], r[13]
        if item is None or str(item).strip() in ("품목", ""): continue
        d = to_date(date)
        if d is None or not isinstance(qty, (int, float)) or str(io) not in ("입고", "출고"):
            continue  # 미지정/수량없음 행 skip
        out.append(dict(cx=str(cx).strip(), gb=str(gb).strip(), date=d, qty=int(qty),
                        io=str(io), item=item, spec=spec))
    wb.close()
    return out


def read_safety():
    wb = openpyxl.load_workbook(SAFE, read_only=True, data_only=True); ws = wb["기준 Table"]
    out = []
    for r in ws.iter_rows(min_row=9, values_only=True):
        no, cx, item, spec, qty, note = r[1], r[4], r[6], r[7], r[8], r[9]
        if no is None: continue
        out.append(dict(no=no, cx=str(cx).strip(), item=item, spec=spec,
                        qty=qty if isinstance(qty, (int, float)) else None,
                        note=str(note or "").strip()))
    wb.close()
    return out


def main():
    conn = pymysql.connect(**DB, autocommit=False); cur = conn.cursor()
    # DB 로드
    cur.execute("SELECT id, product_name, spec, product_id FROM skus")
    sku_by_key = {}
    for sid, pn, sp, pid in cur.fetchall():
        sku_by_key[K(pn, sp)] = dict(id=sid, pn=pn, sp=sp, pid=pid)
    cur.execute("""SELECT st.id, st.sku_id, st.complex_name, st.qty, st.total_in, st.total_out
                   FROM stock st""")
    stock_by = defaultdict(list)  # (sku_id, complex_name) -> [stock rows]
    stock_all = {}
    for sid, sku_id, cxn, qty, ti, to in cur.fetchall():
        stock_by[(sku_id, cxn)].append(sid)
        stock_all[sid] = dict(sku_id=sku_id, cx=cxn, qty=qty or 0, ti=ti or 0, to=to or 0)

    moves = read_moves()
    print(f"[입출고] 원본 유효행 {len(moves)}")
    # 중복 제거 (품목,규격,일자,수량,io 완전동일)
    seen = set(); dedup = []; dups = 0
    for m in moves:
        sig = (norm(m['item']), norm(base_spec(m['spec'])), m['date'], m['qty'], m['io'], m['cx'])
        if sig in seen: dups += 1; continue
        seen.add(sig); dedup.append(m)
    print(f"  중복 제거: -{dups} → {len(dedup)}행")

    # 분류
    skip_del = [m for m in dedup if dkey(m['item'], m['spec']) in DELETE_KEYS]
    work = [m for m in dedup if dkey(m['item'], m['spec']) not in DELETE_KEYS]
    matched, newmv, missing_loc = [], [], []
    for m in work:
        k = K(m['item'], m['spec'])
        if k in sku_by_key:
            sid = sku_by_key[k]['id']
            locs = stock_by.get((sid, m['cx']))
            if locs:
                matched.append((m, sid, locs[0]))
            else:
                missing_loc.append(m)  # SKU는 있는데 그 단지 재고행이 없음
        else:
            if dkey(m['item'], m['spec']) in NEW_OK:
                newmv.append(m)
            # else: net0 보정/헤더 → 무시
    print(f"  매칭(단지 재고행 O) {len(matched)} · 단지행없음 {len(missing_loc)} · 신규대상 {len(newmv)} · 삭제대상행 {len(skip_del)}")
    if missing_loc:
        print("  ⚠️ SKU는 있으나 해당 단지 재고행 없음:")
        for m in Counter((str(x['item']), base_spec(x['spec']), x['cx']) for x in missing_loc).most_common(20):
            print(f"      {m[0]}  x{m[1]}")

    # 결과 재고 시뮬레이션(매칭분)
    sim = {}
    for m, sid, stid in sorted(matched, key=lambda x: x[0]['date']):
        s = sim.setdefault(stid, dict(qty=stock_all[stid]['qty'], ti=0, to=0))
        if m['io'] == '입고': s['qty'] += m['qty']; s['ti'] += m['qty']
        else: s['qty'] += m['qty']; s['to'] += -m['qty']  # qty already negative
    neg = [(stid, s) for stid, s in sim.items() if s['qty'] < 0]
    print(f"\n  시뮬레이션: 재고행 {len(sim)}개 변동 · 음수재고 {len(neg)}개")
    for stid, s in neg[:15]:
        sk = stock_all[stid]
        print(f"     [{sk['cx']}] sku={sk['sku_id'][:8]} → qty={s['qty']}")

    # 안전재고
    safety = read_safety()
    byk = defaultdict(list)
    for r in safety:
        if r['qty'] is not None: byk[K(r['item'], r['spec'])].append(r['qty'])
    saf_upd = {k: max(v) for k, v in byk.items() if k in sku_by_key}
    print(f"\n[안전재고] 갱신대상 {len(saf_upd)}키 (충돌 MAX 포함)")

    # 삭제/변경
    print(f"\n[삭제] {len(DELETE_KEYS)}개 SKU  [품명변경] {RENAME[0]}→{RENAME[2]}")

    if not APPLY:
        print("\n(DRY-RUN) DB 변경 없음. 반영하려면 --apply")
        conn.close(); return

    # ===================== APPLY =====================
    import uuid
    MARK = "[import20260805]"
    cur.execute("SELECT COUNT(*) FROM stock_movements WHERE memo=%s", (MARK,))
    if cur.fetchone()[0] > 0:
        print("\n[중단] 이미 임포트된 이력(memo=%s)이 있습니다. 재실행 방지." % MARK); conn.close(); return

    # 대상 DB에서 동적 조회(로컬/운영 id가 다르므로 하드코딩 금지)
    cur.execute("SELECT id, display_name FROM profiles WHERE username='admin'")
    _a = cur.fetchone(); ADMIN = (_a[0], _a[1]) if _a else (None, "시스템")
    cur.execute("""SELECT complex_name, MIN(complex_id), MIN(storage_location_id), MIN(zone_id), MIN(location_label)
                   FROM stock WHERE complex_name IS NOT NULL GROUP BY complex_name""")
    CX = {r[0]: (r[1], r[2], r[3], r[4] or "기본창고 > 기본위치") for r in cur.fetchall()}
    print(f"[cfg] 대상={'운영' if PROD else '로컬'} · 단지위치 {list(CX.keys())} · admin={ADMIN[1]}")

    def new_stock(sku_id, cxn):
        cid, loc, zid, label = CX[cxn]
        sid = str(uuid.uuid4())
        cur.execute("""INSERT INTO stock (id,sku_id,complex_id,complex_name,storage_location_id,storage_location_code,
            zone_id,location_label,qty,initial_qty,total_in,total_out,status,created_at)
            VALUES (%s,%s,%s,%s,%s,'',%s,%s,0,0,0,0,'out',NOW(6))""", (sid, sku_id, cid, cxn, loc, zid, label))
        stock_all[sid] = dict(sku_id=sku_id, cx=cxn, qty=0, ti=0, to=0)
        stock_by[(sku_id, cxn)].append(sid)
        return sid

    # --- 0) 신규 SKU 3건 생성 ---
    NEW_DEFS = {  # (품목,규격) -> (단지, product_name, existing_product_id_or_None, category_id, category_name)
        ("전기테이프", "20ROLL 1BOX"): ("타워", "전기테이프", "8b6bee12-a581-425b-91f6-d7084c71d2a2", None, None),
        ("힌지", "KING 8500"): ("허브", "힌지", "120054ed-0c2a-4d90-9319-ce5acb4a0d96", None, None),
        ("수중펌프", "1/6HP"): ("타워", "수중펌프", None, "fd704ba8-41f1-40f8-8b8b-0f3a163ba60e", "기계"),
    }
    created = 0
    for m in newmv:
        dk = dkey(m['item'], m['spec'])
        if dk not in NEW_DEFS:
            continue
        cxn, pname, pid, cat_id, cat_name = NEW_DEFS[dk]
        if pid is None:  # 새 product 생성 (수중펌프)
            cur.execute("SELECT MAX(code) FROM products WHERE code LIKE 'P-%'")
            nxt = int(cur.fetchone()[0].split("-")[1]) + 1
            pcode = "P-%06d" % nxt
            pid = str(uuid.uuid4())
            cur.execute("""INSERT INTO products (id,code,name,category_id,category_name,path_label,sku_seq,price,created_at)
                VALUES (%s,%s,%s,%s,%s,%s,0,0,NOW(6))""", (pid, pcode, pname, cat_id, cat_name, cat_name))
            # 코드 시퀀스도 올려 충돌 방지
            cur.execute("UPDATE seq_counters SET val=%s WHERE val<%s AND name LIKE '%%product%%'", (nxt, nxt))
        cur.execute("SELECT code,sku_seq,category_id,category_name,path_label FROM products WHERE id=%s FOR UPDATE", (pid,))
        pcode, seq, pcat, pcatn, plabel = cur.fetchone()
        seq = (seq or 0) + 1
        cur.execute("UPDATE products SET sku_seq=%s WHERE id=%s", (seq, pid))
        code = "%s-%03d" % (pcode, seq)
        sid = str(uuid.uuid4())
        cur.execute("""INSERT INTO skus (id,code,product_id,product_name,spec,category_id,price,safety_stock,qr_generated,created_at)
            VALUES (%s,%s,%s,%s,%s,%s,0,0,1,NOW(6))""", (sid, code, pid, pname, base_spec(m['spec']), pcat))
        sku_by_key[K(m['item'], m['spec'])] = dict(id=sid, pn=pname, sp=base_spec(m['spec']), pid=pid)
        stid = new_stock(sid, cxn)
        matched.append((m, sid, stid))  # 이 신규의 입출고도 반영대상에 추가
        created += 1
    print(f"\n[apply] 신규 SKU 생성 {created}건")

    # --- 1) 입출고 날짜순 반영 (stock_movements + stock) ---
    def sku_code(sid):
        cur.execute("SELECT code FROM skus WHERE id=%s", (sid,)); r = cur.fetchone(); return r[0] if r else ""
    run = {}  # stid -> {qty,ti,to}
    mv_rows = sorted(matched, key=lambda x: (x[0]['date'], x[0]['io'] != '입고'))  # 같은날 입고 먼저
    mvcount = 0
    for m, sid, stid in mv_rows:
        st = run.setdefault(stid, dict(qty=stock_all[stid]['qty'], ti=stock_all[stid]['ti'], to=stock_all[stid]['to']))
        before = st['qty']; delta = m['qty']; after = before + delta
        typ = 'in' if m['io'] == '입고' else 'out'
        if typ == 'in': st['ti'] += delta
        else: st['to'] += -delta
        st['qty'] = after
        cid, loc, zid, label = CX[m['cx']]
        cur.execute("""INSERT INTO stock_movements
            (id,sku_id,stock_id,sku_code,product_name,type,qty,delta,before_qty,after_qty,
             complex_id,complex_name,path_label,memo,reason,usage_place,by_user_id,by_name,at,voided)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'데이터이관',%s,%s,%s,%s,0)""",
            (str(uuid.uuid4()), sid, stid, sku_code(sid), sku_by_key.get(K(m['item'], m['spec']), {}).get('pn', str(m['item'])),
             typ, abs(delta), delta, before, after, cid, m['cx'], label, MARK,
             str(m.get('use') or ''), ADMIN[0], ADMIN[1],
             datetime.datetime.combine(m['date'], datetime.time(12, 0))))
        mvcount += 1
    # stock 반영
    for stid, st in run.items():
        cur.execute("""UPDATE stock SET qty=%s,total_in=%s,total_out=%s,last_moved_at=%s,last_moved_by=%s WHERE id=%s""",
                    (st['qty'], st['ti'], st['to'], datetime.datetime.now(), ADMIN[1], stid))
    print(f"[apply] 입출고 이력 {mvcount}건 · 재고행 {len(run)}개 갱신")

    # --- 2) 안전재고 (삭제대상 제외) ---
    scount = 0
    for k, v in saf_upd.items():
        if k in (K(*d) for d in DELETE_KEYS): continue
        scount += cur.execute("UPDATE skus SET safety_stock=%s WHERE id=%s", (v, sku_by_key[k]['id']))
    print(f"[apply] 안전재고 갱신 {scount}건")

    # --- 3) 삭제 3건 (stock_movements/stock/quote_items 정리 후 sku 삭제) ---
    dcount = 0
    for dk in DELETE_KEYS:
        k = K(*dk)
        if k not in sku_by_key: continue
        sid = sku_by_key[k]['id']
        cur.execute("DELETE FROM stock_movements WHERE sku_id=%s", (sid,))
        cur.execute("DELETE FROM stock WHERE sku_id=%s", (sid,))
        cur.execute("UPDATE quote_items SET sku_id=NULL WHERE sku_id=%s", (sid,))
        dcount += cur.execute("DELETE FROM skus WHERE id=%s", (sid,))
    print(f"[apply] SKU 삭제 {dcount}건")

    # --- 4) 품명변경 ---
    old_i, old_s, new_i = RENAME
    rc = cur.execute("UPDATE skus SET product_name=%s WHERE product_name=%s AND spec=%s", (new_i, old_i, old_s))
    cur.execute("UPDATE products SET name=%s WHERE name=%s", (new_i, old_i))
    print(f"[apply] 품명변경 {rc}건 ({old_i}→{new_i})")

    # --- 5) 재고 상태 재계산 (qty vs 안전재고) ---
    cur.execute("""UPDATE stock st JOIN skus s ON s.id=st.sku_id
        SET st.status = CASE WHEN st.qty<=0 THEN 'out'
                             WHEN st.qty<=s.safety_stock THEN 'low' ELSE 'in_stock' END""")
    print("[apply] 재고상태 재계산 완료")

    conn.commit()
    print("\n[COMMIT 완료]")
    conn.close()


if __name__ == "__main__":
    main()
