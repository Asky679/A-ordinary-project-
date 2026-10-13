package com.careernav.repository;
import com.careernav.entity.JobMarketSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface JobMarketSnapshotRepository extends JpaRepository<JobMarketSnapshotEntity, Long> {
    Optional<JobMarketSnapshotEntity> findFirstByOrderByCreatedAtDesc();
    Optional<JobMarketSnapshotEntity> findByMonthKey(String monthKey);
}
