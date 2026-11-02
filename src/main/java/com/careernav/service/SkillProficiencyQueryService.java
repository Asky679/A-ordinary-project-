package com.careernav.service;

import com.careernav.domain.ApiModels.SkillProficiencyView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 独立查询技能验证画像，避免 ResumeService 与技能测试服务形成循环依赖。 */
@Service
public class SkillProficiencyQueryService {
    private final JdbcTemplate jdbc;

    public SkillProficiencyQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SkillProficiencyView> listByResume(Long resumeId) {
        if (resumeId == null) return List.of();
        return jdbc.query("""
                SELECT resume_id, skill, declared_level, verified_level, score, confidence, evidence_type,
                       bkt_mastery, bkt_confidence, guess_risk, consistency, rounds_completed, updated_at
                FROM skill_proficiency
                WHERE resume_id=?
                ORDER BY updated_at DESC
                """, (rs, rowNum) -> new SkillProficiencyView(
                rs.getLong("resume_id"), rs.getString("skill"), rs.getString("declared_level"),
                rs.getString("verified_level"), (Integer) rs.getObject("score"),
                rs.getBigDecimal("confidence") == null ? null : rs.getBigDecimal("confidence").doubleValue(),
                rs.getString("evidence_type"),
                rs.getBigDecimal("bkt_mastery") == null ? null : rs.getBigDecimal("bkt_mastery").doubleValue(),
                rs.getBigDecimal("bkt_confidence") == null ? null : rs.getBigDecimal("bkt_confidence").doubleValue(),
                rs.getBigDecimal("guess_risk") == null ? null : rs.getBigDecimal("guess_risk").doubleValue(),
                rs.getBigDecimal("consistency") == null ? null : rs.getBigDecimal("consistency").doubleValue(),
                (Integer) rs.getObject("rounds_completed"),
                rs.getTimestamp("updated_at").toLocalDateTime()), resumeId);
    }
}