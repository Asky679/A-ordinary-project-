package com.careernav.repository;

import com.careernav.entity.DiagnosisReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DiagnosisReportRepository extends JpaRepository<DiagnosisReportEntity, Long> {
    Optional<DiagnosisReportEntity> findFirstByResumeIdOrderByCreatedAtDesc(Long resumeId);
    Optional<DiagnosisReportEntity> findFirstByUserIdAndResumeIdOrderByCreatedAtDesc(Long userId, Long resumeId);
    List<DiagnosisReportEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<DiagnosisReportEntity> findByUserIdAndResumeIdOrderByCreatedAtDesc(Long userId, Long resumeId);
    long countByUserId(Long userId);
    void deleteByUserId(Long userId);

    @Modifying
    @Query("update DiagnosisReportEntity r set r.userId = :userId, r.dataKind = 'DEMO' where r.userId is null")
    int assignUnownedToDemo(@Param("userId") Long userId);
}
