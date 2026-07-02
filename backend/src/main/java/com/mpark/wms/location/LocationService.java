package com.mpark.wms.location;

import com.mpark.wms.common.ApiException;
import com.mpark.wms.common.code.CodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class LocationService {

    private final ZoneRepository zoneRepo;
    private final SubZoneRepository subZoneRepo;
    private final StorageLocationRepository storageRepo;
    private final CodeGenerator codeGenerator;

    /* ===================== 구역 ===================== */
    @Transactional(readOnly = true)
    public List<Zone> listZones(String complexId) {
        return isBlank(complexId) ? zoneRepo.findAllByOrderByCreatedAtAsc()
                : zoneRepo.findByComplexIdOrderByCreatedAtAsc(complexId);
    }

    public Zone createZone(ZoneRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("구역명을 입력하세요.");
        Zone z = new Zone();
        z.setName(r.name().trim());
        z.setType(normType(r.type()));
        z.setComplexId(r.complexId());
        z.setComplexName(nz(r.complexName()));
        return zoneRepo.save(z);
    }

    public Zone updateZone(String id, ZoneRequest r) {
        Zone z = zoneRepo.findById(id).orElseThrow(() -> ApiException.notFound("구역을 찾을 수 없습니다."));
        if (!isBlank(r.name())) z.setName(r.name().trim());
        if (!isBlank(r.type())) z.setType(normType(r.type()));
        if (r.complexId() != null) z.setComplexId(r.complexId());
        z.setComplexName(nz(r.complexName()));
        return zoneRepo.save(z);
    }

    public void removeZone(String id) {
        zoneRepo.deleteById(id); // sub_zones 는 FK ON DELETE CASCADE
    }

    /* ===================== 상세구역 ===================== */
    @Transactional(readOnly = true)
    public List<SubZone> listSubZones(String zoneId) {
        return isBlank(zoneId) ? subZoneRepo.findAllByOrderByCreatedAtAsc()
                : subZoneRepo.findByZoneIdOrderByCreatedAtAsc(zoneId);
    }

    public SubZone createSubZone(SubZoneRequest r) {
        if (isBlank(r.name())) throw ApiException.badRequest("상세구역명을 입력하세요.");
        SubZone s = new SubZone();
        s.setName(r.name().trim());
        s.setType(normType(r.type()));
        s.setZoneId(r.zoneId());
        s.setZoneName(nz(r.zoneName()));
        s.setComplexId(r.complexId());
        s.setComplexName(nz(r.complexName()));
        return subZoneRepo.save(s);
    }

    public SubZone updateSubZone(String id, SubZoneRequest r) {
        SubZone s = subZoneRepo.findById(id).orElseThrow(() -> ApiException.notFound("상세구역을 찾을 수 없습니다."));
        if (!isBlank(r.name())) s.setName(r.name().trim());
        if (!isBlank(r.type())) s.setType(normType(r.type()));
        if (r.zoneId() != null) s.setZoneId(r.zoneId());
        s.setZoneName(nz(r.zoneName()));
        if (r.complexId() != null) s.setComplexId(r.complexId());
        s.setComplexName(nz(r.complexName()));
        return subZoneRepo.save(s);
    }

    public void removeSubZone(String id) {
        subZoneRepo.deleteById(id);
    }

    /* ===================== 보관위치 ===================== */
    @Transactional(readOnly = true)
    public List<StorageLocation> listStorage(String complexId) {
        List<StorageLocation> list = isBlank(complexId) ? storageRepo.findAllByOrderByCreatedAtDesc()
                : storageRepo.findByComplexIdOrderByCodeAsc(complexId);
        // 유효 타입 계산: 상세구역 타입 우선, 없으면 구역 타입, 둘 다 없으면 warehouse
        Map<String, String> zoneType = new HashMap<>();
        for (Zone z : zoneRepo.findAll()) zoneType.put(z.getId(), normType(z.getType()));
        Map<String, String> subType = new HashMap<>();
        for (SubZone s : subZoneRepo.findAll()) subType.put(s.getId(), normType(s.getType()));
        for (StorageLocation l : list) {
            String t = l.getSubZoneId() != null ? subType.get(l.getSubZoneId()) : null;
            if (t == null && l.getZoneId() != null) t = zoneType.get(l.getZoneId());
            l.setType(t == null ? "warehouse" : t);
        }
        return list;
    }

    public StorageLocation createStorage(StorageLocationRequest r) {
        StorageLocation s = new StorageLocation();
        s.setCode(codeGenerator.next("storage_locations", "LOC")); // 자동코드
        apply(s, r);
        return storageRepo.save(s);
    }

    public StorageLocation updateStorage(String id, StorageLocationRequest r) {
        StorageLocation s = storageRepo.findById(id).orElseThrow(() -> ApiException.notFound("보관위치를 찾을 수 없습니다."));
        apply(s, r); // code 유지
        return storageRepo.save(s);
    }

    public void removeStorage(String id) {
        storageRepo.deleteById(id);
    }

    private void apply(StorageLocation s, StorageLocationRequest r) {
        s.setName(nz(r.name()));
        s.setComplexId(r.complexId());
        s.setComplexName(nz(r.complexName()));
        s.setZoneId(r.zoneId());
        s.setZoneName(nz(r.zoneName()));
        s.setSubZoneId(r.subZoneId());
        s.setSubZoneName(nz(r.subZoneName()));
        s.setLocationLabel(nz(r.locationLabel()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    /** 허용 타입만 통과, 그 외/빈값은 warehouse */
    private static String normType(String t) {
        if (t == null) return "warehouse";
        return switch (t) { case "usage", "common", "warehouse" -> t; default -> "warehouse"; };
    }
}
