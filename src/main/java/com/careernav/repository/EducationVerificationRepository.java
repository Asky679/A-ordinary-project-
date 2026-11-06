package com.careernav.repository;

import com.careernav.entity.EducationVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface EducationVerificationRepository extends JpaRepository<EducationVerificationEntity, Long> {
    Optional<EducationVerificationEntity> findByResumeId(Long resumeId);

    void deleteByResumeId(Long resumeId);
    /** 兼容旧数据：只要已识别学信网官方二维码，就统一修正为合格学历。 */
    @Modifying
    @Query("""
            update EducationVerificationEntity e
               set e.passed = true,
                   e.status = 'CONSISTENT',
                   e.statusLabel = '二维码核验通过',
                   e.score = 100,
                   e.summary = '已检测到学信网官方二维码，本次学历材料按合格处理。'
             where e.qrStatus = 'OFFICIAL_QR'
            """)
    int acceptOfficialQrVerifications();

}