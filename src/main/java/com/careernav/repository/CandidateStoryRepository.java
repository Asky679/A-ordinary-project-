package com.careernav.repository;

import com.careernav.entity.CandidateStoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CandidateStoryRepository extends JpaRepository<CandidateStoryEntity, Long> {
    List<CandidateStoryEntity> findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    List<CandidateStoryEntity> findTop12ByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    Optional<CandidateStoryEntity> findFirstByResumeIdAndFingerprintAndDeletedAtIsNull(Long resumeId, String fingerprint);

    boolean existsBySourceSessionIdAndDeletedAtIsNull(Long sourceSessionId);

    List<CandidateStoryEntity> findByResumeIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long resumeId);
}