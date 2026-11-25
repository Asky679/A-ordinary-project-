package com.careernav.repository;

import com.careernav.entity.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
    List<InterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<InterviewSessionEntity> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    List<InterviewSessionEntity> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId);

    long countByUserIdAndDeletedAtIsNull(Long userId);

    long countByUserId(Long userId);

    List<InterviewSessionEntity> findByDeletedAtIsNullOrderByUpdatedAtDesc();

    List<InterviewSessionEntity> findByResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId, String status);

    List<InterviewSessionEntity> findByUserIdAndResumeIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId, Long resumeId, String status);

    List<InterviewSessionEntity> findByResumeIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long resumeId);

    List<InterviewSessionEntity> findByDeletedAtIsNotNullOrderByDeletedAtDesc();

    long countByDeletedAtIsNull();
    void deleteByUserId(Long userId);

    @Modifying
    @Query("update InterviewSessionEntity s set s.userId = :userId, s.dataKind = 'DEMO' where s.userId is null")
    int assignUnownedToDemo(@Param("userId") Long userId);

}