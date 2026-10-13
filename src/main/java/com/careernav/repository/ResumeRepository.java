package com.careernav.repository;
import com.careernav.entity.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {}
