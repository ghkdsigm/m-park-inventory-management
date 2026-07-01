package com.mpark.wms.common.code;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeqCounterRepository extends JpaRepository<SeqCounter, String> {

    /** SELECT ... FOR UPDATE — 시퀀스 행을 잠그고 증가시키기 위함 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeqCounter s where s.name = :name")
    Optional<SeqCounter> findByNameForUpdate(@Param("name") String name);
}
