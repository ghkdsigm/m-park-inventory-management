package com.mpark.wms.location;

import com.mpark.wms.audit.AuditService;
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
    private final AuditService auditService;

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
        Zone saved = zoneRepo.save(z);
        auditService.log("위치관리", "구역 생성", saved.getId(), saved.getName(), saved.getComplexName(), null, "name=" + saved.getName() + ", type=" + saved.getType());
        return saved;
    }

    public Zone updateZone(String id, ZoneRequest r) {
        Zone z = zoneRepo.findById(id).orElseThrow(() -> ApiException.notFound("구역을 찾을 수 없습니다."));
        String before = "name=" + z.getName() + ", type=" + z.getType();
        if (!isBlank(r.name())) z.setName(r.name().trim());
        if (!isBlank(r.type())) z.setType(normType(r.type()));
        if (r.complexId() != null) z.setComplexId(r.complexId());
        z.setComplexName(nz(r.complexName()));
        Zone saved = zoneRepo.save(z);
        auditService.log("위치관리", "구역 수정", id, saved.getName(), saved.getComplexName(), before, "name=" + saved.getName() + ", type=" + saved.getType());
        return saved;
    }

    public void removeZone(String id) {
        Zone z = zoneRepo.findById(id).orElse(null);
        zoneRepo.deleteById(id); // sub_zones 는 FK ON DELETE CASCADE
        if (z != null) auditService.log("위치관리", "구역 삭제", id, z.getName(), z.getComplexName(), "name=" + z.getName(), null);
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
        SubZone saved = subZoneRepo.save(s);
        auditService.log("위치관리", "상세구역 생성", saved.getId(), saved.getName(), saved.getZoneName(), null, "name=" + saved.getName() + ", type=" + saved.getType());
        return saved;
    }

    public SubZone updateSubZone(String id, SubZoneRequest r) {
        SubZone s = subZoneRepo.findById(id).orElseThrow(() -> ApiException.notFound("상세구역을 찾을 수 없습니다."));
        String before = "name=" + s.getName() + ", type=" + s.getType();
        if (!isBlank(r.name())) s.setName(r.name().trim());
        if (!isBlank(r.type())) s.setType(normType(r.type()));
        if (r.zoneId() != null) s.setZoneId(r.zoneId());
        s.setZoneName(nz(r.zoneName()));
        if (r.complexId() != null) s.setComplexId(r.complexId());
        s.setComplexName(nz(r.complexName()));
        SubZone saved = subZoneRepo.save(s);
        auditService.log("위치관리", "상세구역 수정", id, saved.getName(), saved.getZoneName(), before, "name=" + saved.getName() + ", type=" + saved.getType());
        return saved;
    }

    public void removeSubZone(String id) {
        SubZone s = subZoneRepo.findById(id).orElse(null);
        subZoneRepo.deleteById(id);
        if (s != null) auditService.log("위치관리", "상세구역 삭제", id, s.getName(), s.getZoneName(), "name=" + s.getName(), null);
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
        StorageLocation saved = storageRepo.save(s);
        auditService.log("위치관리", "보관위치 생성", saved.getId(), saved.getCode(), saved.getComplexName(), null, "name=" + saved.getName() + ", label=" + saved.getLocationLabel());
        return saved;
    }

    public StorageLocation updateStorage(String id, StorageLocationRequest r) {
        StorageLocation s = storageRepo.findById(id).orElseThrow(() -> ApiException.notFound("보관위치를 찾을 수 없습니다."));
        String before = "name=" + s.getName() + ", label=" + s.getLocationLabel();
        apply(s, r); // code 유지
        StorageLocation saved = storageRepo.save(s);
        auditService.log("위치관리", "보관위치 수정", id, saved.getCode(), saved.getComplexName(), before, "name=" + saved.getName() + ", label=" + saved.getLocationLabel());
        return saved;
    }

    public void removeStorage(String id) {
        StorageLocation s = storageRepo.findById(id).orElse(null);
        storageRepo.deleteById(id);
        if (s != null) auditService.log("위치관리", "보관위치 삭제", id, s.getCode(), s.getComplexName(), "name=" + s.getName(), null);
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
