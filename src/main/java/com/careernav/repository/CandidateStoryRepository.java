package com.careernav.repository;

import com.careernav.entity.CandidateStoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CandidateStoryRepository extends JpaRepository<CandidateStoryEntity, Long> {
    List<CandidateStoryEntity> findByUserIdAndResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId, Long resumeId);

    List<CandidateStoryEntity> findTop12ByUserIdAndResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId, Long resumeId);

    Optional<CandidateStoryEntity> findFirstByUserIdAndResumeIdAndFingerprintAndDeletedAtIsNull(Long userId, Long resumeId, String fingerprint);

    Optional<CandidateStoryEntity> findByIdAndUserId(Long id, Long userId);

    List<CandidateStoryEntity> findByUserIdAndResumeIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId, Long resumeId);

    long countByUserId(Long userId);
    void deleteByUserId(Long userId);

    @Modifying
    @Query("update CandidateStoryEntity s set s.userId = :userId, s.dataKind = 'DEMO' where s.userId is null")
    int assignUnownedToDemo(@Param("userId") Long userId);

    List<CandidateStoryEntity> findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    List<CandidateStoryEntity> findTop12ByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    Optional<CandidateStoryEntity> findFirstByResumeIdAndFingerprintAndDeletedAtIsNull(Long resumeId, String fingerprint);

    boolean existsBySourceSessionIdAndDeletedAtIsNull(Long sourceSessionId);

    List<CandidateStoryEntity> findBySourceSessionId(Long sourceSessionId);

    List<CandidateStoryEntity> findByResumeIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long resumeId);
}