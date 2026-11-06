package com.careernav.repository;

import com.careernav.entity.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
    List<InterviewSessionEntity> findByResumeIdAndStatusOrderByUpdatedAtDesc(Long resumeId, String status);
}
