package com.mpark.wms.location;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 위치 REST. 프론트 db.js 의 zones / subZones / storageLocations 와 매핑.
 *   GET /api/zones?complexId=        (없으면 전체, created_at asc)
 *   GET /api/sub-zones?zoneId=
 *   GET /api/storage-locations?complexId=   (complexId 있으면 code asc, 없으면 created_at desc)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService service;

    /* ---------- 구역 ---------- */
    @GetMapping("/zones")
    public List<Zone> listZones(@RequestParam(required = false) String complexId) {
        return service.listZones(complexId);
    }

    @PostMapping("/zones")
    public Zone createZone(@RequestBody ZoneRequest r) { return service.createZone(r); }

    @PutMapping("/zones/{id}")
    public Zone updateZone(@PathVariable String id, @RequestBody ZoneRequest r) { return service.updateZone(id, r); }

    @DeleteMapping("/zones/{id}")
    public void removeZone(@PathVariable String id) { service.removeZone(id); }

    /* ---------- 상세구역 ---------- */
    @GetMapping("/sub-zones")
    public List<SubZone> listSubZones(@RequestParam(required = false) String zoneId) {
        return service.listSubZones(zoneId);
    }

    @PostMapping("/sub-zones")
    public SubZone createSubZone(@RequestBody SubZoneRequest r) { return service.createSubZone(r); }

    @PutMapping("/sub-zones/{id}")
    public SubZone updateSubZone(@PathVariable String id, @RequestBody SubZoneRequest r) { return service.updateSubZone(id, r); }

    @DeleteMapping("/sub-zones/{id}")
    public void removeSubZone(@PathVariable String id) { service.removeSubZone(id); }

    /* ---------- 보관위치 ---------- */
    @GetMapping("/storage-locations")
    public List<StorageLocation> listStorage(@RequestParam(required = false) String complexId) {
        return service.listStorage(complexId);
    }

    @PostMapping("/storage-locations")
    public StorageLocation createStorage(@RequestBody StorageLocationRequest r) { return service.createStorage(r); }

    @PutMapping("/storage-locations/{id}")
    public StorageLocation updateStorage(@PathVariable String id, @RequestBody StorageLocationRequest r) { return service.updateStorage(id, r); }

    @DeleteMapping("/storage-locations/{id}")
    public void removeStorage(@PathVariable String id) { service.removeStorage(id); }
}
