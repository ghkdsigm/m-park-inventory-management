package com.mpark.wms.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileRepository extends JpaRepository<Profile, String> {
    Optional<Profile> findByEmail(String email);
    Optional<Profile> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<Profile> findAllByOrderByCreatedAtAsc();
}
