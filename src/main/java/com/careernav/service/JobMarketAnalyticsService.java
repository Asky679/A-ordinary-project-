package com.careernav.service;

import com.careernav.domain.ApiModels.MarketSnapshotView;
import com.careernav.entity.JobEntity;
import com.careernav.entity.JobMarketSnapshotEntity;
import com.careernav.repository.JobMarketSnapshotRepository;
import com.careernav.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JobMarketAnalyticsService {
    private static final Pattern SALARY_RANGE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~]\\s*(\\d+(?:\\.\\d+)?)\\s*[kK]");
    private static final Pattern SALARY_SINGLE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[kK]");
    private final JobRepository jobRepository;
    private final JobMarketSnapshotRepository snapshotRepository;
    private final JobService jobService;
    private final JsonCodec jsonCodec;

    public JobMarketAnalyticsService(JobRepository jobRepository, JobMarketSnapshotRepository snapshotRepository,
                                     JobService jobService, JsonCodec jsonCodec) {
        this.jobRepository = jobRepository;
        this.snapshotRepository = snapshotRepository;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public MarketSnapshotView captureMonthly() {
        List<JobEntity> all = jobRepository.findAll();
        List<JobEntity> active = all.stream().filter(job -> !"EXPIRED".equalsIgnoreCase(job.getStatus()))
                .filter(job -> job.getValidThrough() == null || !job.getValidThrough().isBefore(LocalDateTime.now())).toList();
        Map<String, Long> cityDemand = active.stream().map(JobEntity::getCity).filter(Objects::nonNull).filter(v -> !v.isBlank())
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        Map<String, Long> skillDemand = active.stream()
                .flatMap(job -> jobService.normalize(jsonCodec.readList(job.getSkillsJson(), String.class)).stream())
                .filter(v -> !v.isBlank()).collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(20)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        String monthKey = YearMonth.now().toString();
        JobMarketSnapshotEntity snapshot = snapshotRepository.findByMonthKey(monthKey).orElseGet(JobMarketSnapshotEntity::new);
        snapshot.setMonthKey(monthKey);
        snapshot.setTotalJobs((long) all.size());
        snapshot.setActiveJobs((long) active.size());
        snapshot.setCityJson(jsonCodec.write(cityDemand));
        snapshot.setSkillJson(jsonCodec.write(skillDemand));
        snapshot.setSalaryJson(jsonCodec.write(salarySummary(active)));
        snapshot.setCreatedAt(LocalDateTime.now());
        return toView(snapshotRepository.save(snapshot));
    }

    @Transactional(readOnly = true)
    public MarketSnapshotView latest() {
        return snapshotRepository.findFirstByOrderByCreatedAtDesc().map(this::toView).orElse(null);
    }

    private Map<String, Object> salarySummary(List<JobEntity> jobs) {
        double minTotal = 0, maxTotal = 0;
        int sampleCount = 0;
        for (JobEntity job : jobs) {
            String value = job.getSalary() == null ? "" : job.getSalary().replace(" ", "");
            Matcher range = SALARY_RANGE.matcher(value);
            if (range.find()) {
                minTotal += Double.parseDouble(range.group(1));
                maxTotal += Double.parseDouble(range.group(2));
                sampleCount++;
                continue;
            }
            Matcher single = SALARY_SINGLE.matcher(value);
            if (single.find()) {
                double amount = Double.parseDouble(single.group(1));
                minTotal += amount; maxTotal += amount; sampleCount++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", sampleCount);
        result.put("currency", "K/月");
        result.put("averageMin", sampleCount == 0 ? 0 : Math.round(minTotal / sampleCount * 10.0) / 10.0);
        result.put("averageMax", sampleCount == 0 ? 0 : Math.round(maxTotal / sampleCount * 10.0) / 10.0);
        return result;
    }

    @SuppressWarnings("unchecked")
    private MarketSnapshotView toView(JobMarketSnapshotEntity entity) {
        Map<String, Object> cities = jsonCodec.read(entity.getCityJson(), Map.class);
        Map<String, Object> skills = jsonCodec.read(entity.getSkillJson(), Map.class);
        Map<String, Object> salary = jsonCodec.read(entity.getSalaryJson(), Map.class);
        return new MarketSnapshotView(entity.getId(), entity.getMonthKey(),
                entity.getTotalJobs() == null ? 0 : entity.getTotalJobs(),
                entity.getActiveJobs() == null ? 0 : entity.getActiveJobs(),
                cities == null ? Map.of() : cities, skills == null ? Map.of() : skills,
                salary == null ? Map.of() : salary, entity.getCreatedAt());
    }
}
