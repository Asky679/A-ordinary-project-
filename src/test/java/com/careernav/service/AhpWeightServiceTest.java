package com.careernav.service;

import com.careernav.entity.JobEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AhpWeightServiceTest {
    @Test
    void calculatesSixDimensionalWeightsAndPassesConsistencyCheck() {
        AhpWeightService service = new AhpWeightService();
        var profile = service.profile();

        assertNotNull(profile);
        assertEquals("AHP principal eigenvector", profile.method());
        assertEquals(6, profile.criteria().size());
        assertEquals(6, profile.pairwiseMatrix().size());
        assertEquals(1.0, profile.weightSum(), 1e-9);
        assertEquals(1.0, profile.criteria().stream()
                .mapToDouble(item -> item.weight()).sum(), 1e-6);
        assertEquals(100, profile.baselineWeights().skills()
                + profile.baselineWeights().role()
                + profile.baselineWeights().experience()
                + profile.baselineWeights().education()
                + profile.baselineWeights().city()
                + profile.baselineWeights().evidence());
        assertEquals(34, profile.baselineWeights().skills());
        assertEquals(22, profile.baselineWeights().role());
        assertEquals(16, profile.baselineWeights().experience());
        assertEquals(10, profile.baselineWeights().education());
        assertEquals(4, profile.baselineWeights().city());
        assertEquals(14, profile.baselineWeights().evidence());
        assertTrue(profile.consistencyRatio() < 0.10);
        assertTrue(profile.consistent());
        assertFalse(profile.jobAdjusted());
    }

    @Test
    void pairwiseMatrixUsesReciprocalValues() {
        AhpWeightService service = new AhpWeightService();
        var matrix = service.profile().pairwiseMatrix();
        for (int i = 0; i < matrix.size(); i++) {
            assertEquals(1.0, matrix.get(i).get(i), 1e-9);
            for (int j = 0; j < matrix.size(); j++) {
                assertEquals(1.0, matrix.get(i).get(j) * matrix.get(j).get(i), 1e-5);
            }
        }
    }

    @Test
    void selectedJobAdjustsWeightsAndKeepsUnitSum() {
        AhpWeightService service = new AhpWeightService();
        JobEntity job = new JobEntity();
        job.setId(99L);
        job.setTitle("Java后端开发实习生");
        job.setCategory("后端开发");
        job.setExperience("在校生/应届生");
        job.setEducation("本科");
        job.setSkillsJson("[\"Java\",\"Spring Boot\",\"MySQL\",\"Redis\",\"Docker\"]");
        job.setJobRequirements("有实际项目经验，熟悉微服务和高并发优先");
        job.setJobDescription("参与后端系统开发与工程落地");

        var profile = service.profileForJob(job);
        var global = service.profile();

        assertTrue(profile.jobAdjusted());
        assertEquals(99L, profile.jobId());
        assertEquals(1.0, profile.weightSum(), 1e-9);
        assertEquals(1.0, profile.criteria().stream()
                .mapToDouble(item -> item.weight()).sum(), 1e-6);
        assertFalse(profile.adjustmentReasons().isEmpty());
        assertTrue(weight(profile, "experience") < weight(global, "experience"));
        assertTrue(weight(profile, "evidence") > weight(global, "evidence"));
    }

    private double weight(com.careernav.domain.ApiModels.AhpWeightProfile profile, String key) {
        return profile.criteria().stream()
                .filter(item -> item.key().equals(key))
                .findFirst().orElseThrow().weight();
    }
}