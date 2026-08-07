#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
S3 상품사진(파일명 = 품명)을 SKU.product_name 과 정규화 매칭해 skus.image_url 을 채운다.

결정된 규칙:
  · 정규화 매칭  : 확장자 제거 + NFC + casefold + 앞뒤/중복 공백 정리 후 비교
  · 전부 덮어쓰기 : 매칭되면 기존 image_url 무시하고 갱신
  · 같은 품명 다중 SKU : 전부 같은 이미지(image_url)

사용:
  # 미리보기(기본, DB 변경 없음)
  python scripts/match_sku_images.py
  # 실제 반영
  python scripts/match_sku_images.py --apply

환경변수(기본값은 로컬 docker compose 기준):
  DB_HOST=127.0.0.1  DB_PORT=3307  DB_NAME=mpark_wms  DB_USER=mpark  DB_PASS=mpark
  S3_BUCKET=dwe-on-mpark
  S3_PREFIX=mpark/stock/image/product/
  # S3 호환 스토리지(사내 ECS/MinIO/네이버·NHN 등)면 엔드포인트 필수. 지정 시 path-style 로 접속.
  S3_ENDPOINT=            예) https://obj.dongwha.com  (미지정 시 진짜 AWS 로 접속)
  # image_url 로 저장할 공개 URL 접두사. 미지정 시 엔드포인트/리전으로 자동 구성.
  IMG_BASE_URL=            예) https://obj.dongwha.com/dwe-on-mpark
  AWS_REGION=ap-northeast-2   # AWS/일부 호환 스토리지의 서명 리전
  # 자격증명은 표준 방식(AWS_ACCESS_KEY_ID/SECRET, 프로필 등)으로 주입.
"""
import os
import sys
import re
import unicodedata
from urllib.parse import quote

import boto3
from botocore.config import Config
import pymysql

DB = dict(
    host=os.getenv("DB_HOST", "127.0.0.1"),
    port=int(os.getenv("DB_PORT", "3307")),
    db=os.getenv("DB_NAME", "mpark_wms"),
    user=os.getenv("DB_USER", "mpark"),
    password=os.getenv("DB_PASS", "mpark"),
    charset="utf8mb4",
)
BUCKET = os.getenv("S3_BUCKET", "dwe-on-mpark")
PREFIX = os.getenv("S3_PREFIX", "mpark/stock/image/product/")
REGION = os.getenv("AWS_REGION", "ap-northeast-2")
ENDPOINT = os.getenv("S3_ENDPOINT", "").rstrip("/")  # S3 호환 스토리지면 필수

# image_url 접두사: 명시값 우선 → 엔드포인트가 있으면 path-style(<endpoint>/<bucket>) → 없으면 AWS 가상호스트
IMG_BASE_URL = os.getenv("IMG_BASE_URL", "").rstrip("/") or (
    f"{ENDPOINT}/{BUCKET}" if ENDPOINT else f"https://{BUCKET}.s3.{REGION}.amazonaws.com"
)


def s3_client():
    kwargs = dict(region_name=REGION, config=Config(retries={"max_attempts": 2}))
    if ENDPOINT:
        kwargs["endpoint_url"] = ENDPOINT
        # 사내/호환 스토리지는 대개 path-style 필요(버킷명이 호스트 서브도메인이 아님)
        kwargs["config"] = Config(retries={"max_attempts": 2}, s3={"addressing_style": "path"})
    return boto3.client("s3", **kwargs)

IMG_EXT = re.compile(r"\.(jpe?g|png|webp|gif|bmp|tif?f)$", re.I)
APPLY = "--apply" in sys.argv


def norm(s: str) -> str:
    """정규화: NFC + 소문자 + 앞뒤/중복 공백 정리."""
    if s is None:
        return ""
    s = unicodedata.normalize("NFC", s).strip().casefold()
    return re.sub(r"\s+", " ", s)


def stem(key: str) -> str:
    """S3 key → 파일명(확장자 제거)."""
    name = key.rsplit("/", 1)[-1]
    return IMG_EXT.sub("", name)


def public_url(key: str) -> str:
    # 경로 각 세그먼트를 URL 인코딩(한글 파일명 대응). '/' 는 보존.
    return IMG_BASE_URL + "/" + quote(key, safe="/")


def list_images():
    """prefix 하위 이미지들을 { normalized_stem: key } 로. 중복 정규화명은 마지막 것."""
    s3 = s3_client()
    out = {}
    dup = []
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=BUCKET, Prefix=PREFIX):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            if key.endswith("/") or not IMG_EXT.search(key):
                continue
            n = norm(stem(key))
            if not n:
                continue
            if n in out and out[n] != key:
                dup.append((n, out[n], key))
            out[n] = key
    return out, dup


def main():
    print(f"[cfg] bucket=s3://{BUCKET}/{PREFIX}")
    print(f"[cfg] endpoint = {ENDPOINT or '(AWS 기본)'}")
    print(f"[cfg] image url base = {IMG_BASE_URL}")
    print(f"[cfg] mode = {'APPLY (DB 갱신)' if APPLY else 'DRY-RUN (미리보기)'}\n")

    images, dup = list_images()
    print(f"[s3] 이미지 {len(images)}개 로드" + (f", 정규화 중복 {len(dup)}건" if dup else ""))
    for n, a, b in dup[:20]:
        print(f"      · 중복 '{n}': {a}  ↔  {b}")

    conn = pymysql.connect(**DB, autocommit=False)
    matched, missed, used_keys = [], [], set()
    with conn.cursor() as cur:
        cur.execute("SELECT id, product_name, image_url FROM skus")
        rows = cur.fetchall()
        for sku_id, pname, cur_url in rows:
            key = images.get(norm(pname))
            if key:
                matched.append((sku_id, pname, public_url(key)))
                used_keys.add(key)
            else:
                missed.append(pname)

    print(f"\n[sku] 전체 {len(rows)}개 · 매칭 {len(matched)} · 미매칭 {len(missed)}")
    unused = [k for k in images.values() if k not in used_keys]
    print(f"[s3] 사용되지 않은 이미지 {len(unused)}개")

    # 샘플 출력
    print("\n-- 매칭 샘플 (최대 15) --")
    for sid, pn, url in matched[:15]:
        print(f"  ✓ {pn}  →  {url}")
    if missed:
        uniq_missed = sorted(set(m for m in missed if m))
        print(f"\n-- 미매칭 품명 (고유 {len(uniq_missed)}, 최대 30) --")
        for m in uniq_missed[:30]:
            print(f"  ✗ {m}")
    if unused:
        print(f"\n-- 미사용 이미지 (최대 30) --")
        for k in unused[:30]:
            print(f"  · {stem(k)}")

    if not APPLY:
        print("\n(DRY-RUN) 변경 없음. 반영하려면  --apply  붙여 실행.")
        conn.close()
        return

    with conn.cursor() as cur:
        cur.executemany(
            "UPDATE skus SET image_url=%s WHERE id=%s",
            [(url, sid) for sid, _pn, url in matched],
        )
    conn.commit()
    conn.close()
    print(f"\n[apply] {len(matched)}개 SKU image_url 갱신 완료.")


if __name__ == "__main__":
    main()
