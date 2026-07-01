package com.mpark.wms.location;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ZoneRepository extends JpaRepository<Zone, String> {
    List<Zone> findAllByOrderByCreatedAtAsc();
    List<Zone> findByComplexIdOrderByCreatedAtAsc(String complexId);
}

interface SubZoneRepository extends JpaRepository<SubZone, String> {
    List<SubZone> findAllByOrderByCreatedAtAsc();
    List<SubZone> findByZoneIdOrderByCreatedAtAsc(String zoneId);
}

interface StorageLocationRepository extends JpaRepository<StorageLocation, String> {
    List<StorageLocation> findAllByOrderByCreatedAtDesc();
    List<StorageLocation> findByComplexIdOrderByCodeAsc(String complexId);
}
