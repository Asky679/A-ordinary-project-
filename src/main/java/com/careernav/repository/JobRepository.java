package com.careernav.repository;

import com.careernav.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<JobEntity, Long> {
    Optional<JobEntity> findByExternalId(String externalId);
    List<JobEntity> findBySource(String source);

    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'ACTIVE'
              AND (:keyword = '' OR title ILIKE CONCAT('%', :keyword, '%')
                   OR company ILIKE CONCAT('%', :keyword, '%')
                   OR COALESCE(search_text, '') ILIKE CONCAT('%', :keyword, '%'))
              AND (:city = '' OR city ILIKE CONCAT('%', :city, '%'))
            ORDER BY dataset_year DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<JobEntity> searchJobs(@Param("keyword") String keyword,
                               @Param("city") String city,
                               @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'ACTIVE'
              AND id <> :id
              AND (:category = '' OR category = :category)
              AND (:city = '' OR city = :city)
            ORDER BY dataset_year DESC NULLS LAST, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<JobEntity> findSimilar(@Param("id") Long id, @Param("category") String category,
                                @Param("city") String city, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'ACTIVE'
              AND (:city = '' OR city ILIKE CONCAT('%', :city, '%'))
            ORDER BY dataset_year DESC NULLS LAST, id DESC
            LIMIT 5000
            """, nativeQuery = true)
    List<JobEntity> findRecommendationCandidates(@Param("city") String city);
}
