package com.careernav.repository;

import com.careernav.entity.ResumeVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersionEntity, Long> {
    List<ResumeVersionEntity> findByResumeIdOrderByVersionNoDesc(Long resumeId);
    Optional<ResumeVersionEntity> findByIdAndResumeId(Long id, Long resumeId);
    long countByResumeId(Long resumeId);
    void deleteByResumeId(Long resumeId);
}
