package com.careernav.repository;

import com.careernav.entity.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
    List<InterviewSessionEntity> findByDeletedAtIsNullOrderByUpdatedAtDesc();

    List<InterviewSessionEntity> findByResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId, String status);

    List<InterviewSessionEntity> findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    List<InterviewSessionEntity> findByDeletedAtIsNotNullOrderByDeletedAtDesc();

    long countByDeletedAtIsNull();
}