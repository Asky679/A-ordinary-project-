package com.careernav.service;

import com.careernav.domain.ApiModels.AhpCriterionWeight;
import com.careernav.domain.ApiModels.AhpWeightProfile;
import com.careernav.domain.ApiModels.AgentMatchWeights;
import com.careernav.entity.JobEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AHP 六维权重。
 *
 * <p>全局权重由专家判断矩阵的主特征向量得到；选择具体岗位后，再根据岗位标题、
 * 技能数量、经验级别和任职要求生成岗位条件化的 AHP 权重向量。两种结果都严格
 * 归一化，六个归一化权重之和为 1。</p>
 */
@Service
public class AhpWeightService {
    private static final double CONSISTENCY_LIMIT = 0.10;
    private static final double RANDOM_INDEX_6 = 1.24;
    private static final String[] KEYS = {"skills", "role", "experience", "education", "city", "evidence"};
    private static final String[] LABELS = {"技能匹配", "岗位方向", "工作经验", "学历背景", "城市偏好", "项目证据"};
    private static final int SKILLS = 0, ROLE = 1, EXPERIENCE = 2, EDUCATION = 3, CITY = 4, EVIDENCE = 5;

    // 系统内置初始判断矩阵。后续可替换为就业指导专家或企业导师的评审矩阵。
    private static final double[][] MATRIX = {
            {1.0, 2.0, 3.0, 3.0, 7.0, 2.0},
            {1.0 / 2, 1.0, 2.0, 2.0, 5.0, 2.0},
            {1.0 / 3, 1.0 / 2, 1.0, 2.0, 4.0, 2.0},
            {1.0 / 3, 1.0 / 2, 1.0 / 2, 1.0, 3.0, 1.0 / 2},
            {1.0 / 7, 1.0 / 5, 1.0 / 4, 1.0 / 3, 1.0, 1.0 / 5},
            {1.0 / 2, 1.0 / 2, 1.0 / 2, 2.0, 5.0, 1.0}
    };
    private static final Pattern EXPERIENCE_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~至]\\s*(\\d+(?:\\.\\d+)?)\\s*年");
    private static final Pattern EXPERIENCE_ABOVE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*年(?:以上|及以上)");
    private static final Pattern JSON_SKILL = Pattern.compile("\"([^\"]+)\"");

    private final double[] baseVector;
    private final AhpWeightProfile baseProfile;

    public AhpWeightService() {
        this.baseVector = principalVector();
        this.baseProfile = buildProfile(baseVector, null, List.of());
    }

    /** 全局六维 AHP 基准权重。 */
    public AhpWeightProfile profile() {
        return baseProfile;
    }

    /** 选择具体岗位后，生成该岗位的 AHP 调整权重。 */
    public AhpWeightProfile profileForJob(JobEntity job) {
        if (job == null) return baseProfile;
        double[] adjusted = baseVector.clone();
        List<String> reasons = new ArrayList<>();
        String title = safe(job.getTitle());
        String text = String.join(" ", title, safe(job.getCategory()), safe(job.getExperience()),
                safe(job.getEducation()), safe(job.getJobRequirements()), safe(job.getJobDescription()),
                safe(job.getDescription()), safe(job.getSkillsJson())).toLowerCase(Locale.ROOT);
        int minimumYears = minimumExperienceYears(text);
        boolean entryLevel = text.contains("实习") || text.contains("应届") || text.contains("校招")
                || text.contains("在校") || text.contains("毕业生") || (minimumYears >= 0 && minimumYears <= 1);
        boolean senior = minimumYears >= 3 || containsAny(title, "高级", "资深", "专家", "架构师", "技术专家");

        if (entryLevel) {
            multiply(adjusted, EXPERIENCE, 0.68);
            multiply(adjusted, EVIDENCE, 1.15);
            multiply(adjusted, SKILLS, 1.08);
            multiply(adjusted, EDUCATION, 1.03);
            reasons.add("实习/校招/低经验门槛：降低工作经验权重，提高技能与项目证据权重");
        } else if (senior) {
            multiply(adjusted, EXPERIENCE, 1.38);
            multiply(adjusted, EVIDENCE, 1.18);
            multiply(adjusted, SKILLS, 1.05);
            multiply(adjusted, EDUCATION, 0.90);
            reasons.add("高级/资深岗位：提高工作经验与项目证据权重，适当降低学历权重");
        }

        int skillCount = skillCount(job.getSkillsJson());
        if (skillCount >= 5) {
            multiply(adjusted, SKILLS, 1.10);
            multiply(adjusted, ROLE, 1.04);
            multiply(adjusted, EVIDENCE, 1.06);
            reasons.add("岗位技能栈较宽：提高技能匹配、岗位方向与项目证据权重");
        }

        if (containsAny(text, "高并发", "分布式", "微服务", "架构设计", "性能优化", "源码", "jvm", "内核")) {
            multiply(adjusted, SKILLS, 1.10);
            multiply(adjusted, EXPERIENCE, 1.07);
            multiply(adjusted, EVIDENCE, 1.08);
            reasons.add("岗位强调高并发、分布式或架构能力：提高技能、经验与项目证据权重");
        }

        if (containsAny(text, "项目经验", "工程化", "落地", "交付", "实践经历")) {
            multiply(adjusted, EVIDENCE, 1.12);
            reasons.add("任职要求强调工程落地：提高项目证据权重");
        }

        if (containsAny(text, "硕士及以上", "博士", "硕士研究生", "博士研究生")) {
            multiply(adjusted, EDUCATION, 1.25);
            reasons.add("岗位明确硕士或博士要求：提高学历背景权重");
        }

        if (containsAny(text, "驻场", "出差", "现场", "本地到岗")) {
            multiply(adjusted, CITY, 1.35);
            reasons.add("岗位包含驻场、出差或现场要求：提高城市偏好权重");
        }

        if (reasons.isEmpty()) {
            reasons.add("岗位未触发额外修正，沿用全局 AHP 基准权重");
        }
        return buildProfile(adjusted, job, List.copyOf(reasons));
    }

    public AgentMatchWeights weightsForJob(JobEntity job) {
        return profileForJob(job).baselineWeights();
    }

    public AgentMatchWeights baselineWeights() {
        return baseProfile.baselineWeights();
    }

    private AhpWeightProfile buildProfile(double[] source, JobEntity job, List<String> reasons) {
        double[] vector = normalize(source);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = Math.max(0.02, Math.min(0.55, vector[i]));
        }
        vector = roundToUnitSum(vector);
        int[] percent = allocatePercent(vector);

        List<AhpCriterionWeight> criteria = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            criteria.add(new AhpCriterionWeight(KEYS[i], LABELS[i], vector[i], percent[i]));
        }
        List<List<Double>> matrixView = Arrays.stream(MATRIX)
                .map(row -> Arrays.stream(row).map(this::round6).boxed().toList())
                .toList();
        AgentMatchWeights scoreWeights = new AgentMatchWeights(
                percent[SKILLS], percent[ROLE], percent[EXPERIENCE],
                percent[EDUCATION], percent[CITY], percent[EVIDENCE]);

        double[] weighted = multiply(MATRIX, baseVector);
        double lambdaMax = 0;
        for (int i = 0; i < baseVector.length; i++) lambdaMax += weighted[i] / baseVector[i];
        lambdaMax /= baseVector.length;
        double consistencyIndex = Math.max(0, (lambdaMax - baseVector.length) / (baseVector.length - 1.0));
        double consistencyRatio = consistencyIndex / RANDOM_INDEX_6;
        double weightSum = round6(Arrays.stream(vector).sum());
        boolean adjusted = job != null;

        return new AhpWeightProfile(
                adjusted ? "AHP principal eigenvector + job-context calibration" : "AHP principal eigenvector",
                "Saaty 1-9 reciprocal scale",
                job == null ? null : job.getId(),
                job == null ? null : job.getTitle(),
                adjusted,
                List.copyOf(criteria),
                matrixView,
                round6(lambdaMax),
                round6(consistencyIndex),
                round6(consistencyRatio),
                RANDOM_INDEX_6,
                consistencyRatio < CONSISTENCY_LIMIT,
                weightSum,
                scoreWeights,
                reasons,
                adjusted
                        ? "基于全局 AHP 基准，根据所选岗位的技能密度、经验级别和任职要求实时调整；六项归一化权重之和为 1。"
                        : "全局 AHP 基准权重；选择具体岗位后会在此基础上实时调整。"
        );
    }

    private double[] principalVector() {
        double[] vector = new double[KEYS.length];
        Arrays.fill(vector, 1.0 / KEYS.length);
        for (int iteration = 0; iteration < 200; iteration++) {
            double[] next = normalize(multiply(MATRIX, vector));
            double delta = 0;
            for (int i = 0; i < vector.length; i++) delta = Math.max(delta, Math.abs(next[i] - vector[i]));
            vector = next;
            if (delta < 1e-12) break;
        }
        return vector;
    }

    private double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[vector.length];
        for (int i = 0; i < matrix.length; i++) {
            double value = 0;
            for (int j = 0; j < vector.length; j++) value += matrix[i][j] * vector[j];
            result[i] = value;
        }
        return result;
    }

    private double[] normalize(double[] vector) {
        double[] result = vector.clone();
        double total = Arrays.stream(result).sum();
        if (total <= 0) return new double[]{1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6, 1.0 / 6};
        for (int i = 0; i < result.length; i++) result[i] /= total;
        return result;
    }

    /** 保留 6 位小数，并把舍入误差校正到最大权重项，确保权重和为 1。 */
    private double[] roundToUnitSum(double[] vector) {
        double[] result = Arrays.stream(vector).map(this::round6).toArray();
        double delta = 1.0 - Arrays.stream(result).sum();
        int largest = 0;
        for (int i = 1; i < result.length; i++) if (result[i] > result[largest]) largest = i;
        result[largest] = round6(result[largest] + delta);
        return result;
    }

    private int[] allocatePercent(double[] vector) {
        int[] result = new int[vector.length];
        double[] remainders = new double[vector.length];
        int used = 0;
        for (int i = 0; i < vector.length; i++) {
            double scaled = vector[i] * 100.0;
            result[i] = (int) Math.floor(scaled);
            remainders[i] = scaled - result[i];
            used += result[i];
        }
        Integer[] order = new Integer[vector.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> remainders[i]).reversed());
        for (int i = 0; i < 100 - used; i++) result[order[i % order.length]]++;
        return result;
    }

    private int minimumExperienceYears(String text) {
        Matcher range = EXPERIENCE_RANGE.matcher(text);
        if (range.find()) return (int) Math.floor(Double.parseDouble(range.group(1)));
        Matcher above = EXPERIENCE_ABOVE.matcher(text);
        if (above.find()) return (int) Math.floor(Double.parseDouble(above.group(1)));
        return -1;
    }

    private int skillCount(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank()) return 0;
        Matcher matcher = JSON_SKILL.matcher(skillsJson);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private boolean containsAny(String text, String... terms) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return Arrays.stream(terms).anyMatch(term -> value.contains(term.toLowerCase(Locale.ROOT)));
    }

    private void multiply(double[] values, int index, double factor) {
        values[index] *= factor;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}