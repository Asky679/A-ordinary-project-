package com.careernav.repository;
import com.careernav.entity.DiagnosisReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DiagnosisReportRepository extends JpaRepository<DiagnosisReportEntity, Long> {
    Optional<DiagnosisReportEntity> findFirstByResumeIdOrderByCreatedAtDesc(Long resumeId);
}
