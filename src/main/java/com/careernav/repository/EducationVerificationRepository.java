package com.careernav.repository;

import com.careernav.entity.EducationVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EducationVerificationRepository extends JpaRepository<EducationVerificationEntity, Long> {
    Optional<EducationVerificationEntity> findByResumeId(Long resumeId);

    void deleteByResumeId(Long resumeId);
}