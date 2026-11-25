package com.careernav.repository;

import com.careernav.entity.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {
    List<ResumeEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    Optional<ResumeEntity> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
    void deleteByUserId(Long userId);

    @Modifying
    @Query("update ResumeEntity r set r.userId = :userId, r.dataKind = 'DEMO' where r.userId is null")
    int assignUnownedToDemo(@Param("userId") Long userId);
}
