package com.careernav.repository;
import com.careernav.entity.JobSourcePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.Optional;
public interface JobSourcePolicyRepository extends JpaRepository<JobSourcePolicyEntity, Long> {
    Optional<JobSourcePolicyEntity> findBySourceCode(String sourceCode);
    List<JobSourcePolicyEntity> findAllByOrderBySourceNameAsc();
}
