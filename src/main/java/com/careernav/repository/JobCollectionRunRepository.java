package com.careernav.repository;
import com.careernav.entity.JobCollectionRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface JobCollectionRunRepository extends JpaRepository<JobCollectionRunEntity, Long> {
    List<JobCollectionRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
