package com.mpark.wms.location;

/** 위치 생성/수정 요청 DTO (프론트가 비정규화 이름까지 보냄). storage 의 code 는 단지별 수동 입력(A01 등). */
record ZoneRequest(String name, String complexId, String complexName, String type) {}

record SubZoneRequest(String name, String zoneId, String zoneName, String complexId, String complexName, String type) {}

record StorageLocationRequest(String code, String name, String complexId, String complexName,
                             String zoneId, String zoneName,
                             String subZoneId, String subZoneName, String locationLabel) {}
