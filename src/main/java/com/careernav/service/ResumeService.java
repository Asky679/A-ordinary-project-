package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumePayload;
import com.careernav.domain.ApiModels.ResumeView;
import com.careernav.entity.EducationVerificationEntity;
import com.careernav.entity.ResumeEntity;
import com.careernav.repository.EducationVerificationRepository;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.ResumeVersionRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ResumeService {
    private final ResumeRepository repository;
    private final ResumeVersionRepository versionRepository;
    private final EducationVerificationRepository verificationRepository;
    private final SkillProficiencyQueryService skillProficiencyQueryService;
    private final JsonCodec jsonCodec;

    public ResumeService(ResumeRepository repository, ResumeVersionRepository versionRepository,
                         EducationVerificationRepository verificationRepository,
                         SkillProficiencyQueryService skillProficiencyQueryService, JsonCodec jsonCodec) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.verificationRepository = verificationRepository;
        this.skillProficiencyQueryService = skillProficiencyQueryService;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(readOnly = true)
    public List<ResumeView> list() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ResumeView get(Long id) {
        return toView(getEntity(id));
    }

    @Transactional(readOnly = true)
    public ResumeEntity getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException("简历不存在：" + id));
    }

    @Transactional
    public ResumeView create(ResumePayload payload) {
        ResumeEntity entity = new ResumeEntity();
        copy(payload, entity);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toView(repository.save(entity));
    }

    @Transactional
    public ResumeView update(Long id, ResumePayload payload) {
        return update(id, payload, false);
    }

    @Transactional
    public ResumeView update(Long id, ResumePayload payload, boolean force) {
        ResumeEntity entity = getEntity(id);
        if (!force) {
            List<String> changed = changedLockedFields(entity, payload);
            if (!changed.isEmpty()) {
                throw new BusinessException("以下事实字段已锁定，不能修改：" + String.join("、", changed)
                        + "。请先解锁，或选择强制保存。");
            }
        }
        boolean invalidateVerification = educationFactsChanged(entity, payload);
        copy(payload, entity);
        entity.setUpdatedAt(LocalDateTime.now());
        ResumeEntity saved = repository.save(entity);
        if (invalidateVerification) verificationRepository.deleteByResumeId(id);
        return toView(saved);
    }

    @Transactional
    public ResumeView applyWithoutLockCheck(Long id, ResumePayload payload) {
        ResumeEntity entity = getEntity(id);
        boolean invalidateVerification = educationFactsChanged(entity, payload);
        copy(payload, entity);
        entity.setUpdatedAt(LocalDateTime.now());
        ResumeEntity saved = repository.save(entity);
        if (invalidateVerification) verificationRepository.deleteByResumeId(id);
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new BusinessException("简历不存在：" + id);
        verificationRepository.deleteByResumeId(id);
        versionRepository.deleteByResumeId(id);
        repository.deleteById(id);
    }

    public ResumeView toView(ResumeEntity entity) {
        EducationVerificationEntity verification = verificationRepository.findByResumeId(entity.getId()).orElse(null);
        return new ResumeView(
                entity.getId(), entity.getOwnerName(), entity.getTargetRole(), entity.getCity(),
                entity.getPhone(), entity.getEmail(), entity.getEducation(), entity.getSchool(),
                entity.getMajor(), entity.getGraduationYear(),
                jsonCodec.readList(entity.getSkillsJson(), String.class),
                jsonCodec.readList(entity.getProjectsJson(), ProjectItem.class),
                jsonCodec.readList(entity.getExperiencesJson(), ExperienceItem.class),
                entity.getSelfEvaluation(), entity.getRawText(),
                skillProficiencyQueryService.listByResume(entity.getId()),
                verification == null ? "UNVERIFIED" : verification.getStatus(),
                verification == null ? "未核验" : verification.getStatusLabel(),
                verification == null ? null : verification.getScore(),
                verification == null ? null : verification.getConfidence(),
                verification != null && Boolean.TRUE.equals(verification.getPassed()),
                verification == null ? null : verification.getCheckedAt(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private boolean educationFactsChanged(ResumeEntity entity, ResumePayload payload) {
        return !Objects.equals(normalize(entity.getOwnerName()), normalize(payload.ownerName()))
                || !Objects.equals(normalize(entity.getSchool()), normalize(payload.school()))
                || !Objects.equals(normalize(entity.getEducation()), normalize(payload.education()))
                || !Objects.equals(normalize(entity.getMajor()), normalize(payload.major()))
                || !Objects.equals(entity.getGraduationYear(), payload.graduationYear());
    }

    private List<String> changedLockedFields(ResumeEntity entity, ResumePayload payload) {
        List<String> locks = jsonCodec.readList(entity.getFactLocksJson(), String.class);
        if (locks.isEmpty()) return List.of();

        Map<String, String> current = new LinkedHashMap<>();
        Map<String, String> next = new LinkedHashMap<>();
        current.put("ownerName", normalize(entity.getOwnerName()));
        next.put("ownerName", normalize(payload.ownerName()));
        current.put("phone", normalize(entity.getPhone()));
        next.put("phone", normalize(payload.phone()));
        current.put("email", normalize(entity.getEmail()));
        next.put("email", normalize(payload.email()));
        current.put("school", normalize(entity.getSchool()));
        next.put("school", normalize(payload.school()));
        current.put("education", normalize(entity.getEducation()));
        next.put("education", normalize(payload.education()));
        current.put("major", normalize(entity.getMajor()));
        next.put("major", normalize(payload.major()));
        current.put("graduationYear", entity.getGraduationYear() == null ? "" : String.valueOf(entity.getGraduationYear()));
        next.put("graduationYear", payload.graduationYear() == null ? "" : String.valueOf(payload.graduationYear()));

        List<String> changed = new ArrayList<>();
        for (String field : locks) {
            if (!Objects.equals(current.getOrDefault(field, ""), next.getOrDefault(field, ""))) {
                changed.add(lockLabel(field));
            }
        }
        return changed;
    }

    private String lockLabel(String field) {
        return switch (field) {
            case "ownerName" -> "姓名";
            case "phone" -> "手机号";
            case "email" -> "邮箱";
            case "school" -> "学校";
            case "education" -> "学历";
            case "major" -> "专业";
            case "graduationYear" -> "毕业年份";
            default -> field;
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void copy(ResumePayload payload, ResumeEntity entity) {
        entity.setOwnerName(payload.ownerName().trim());
        entity.setTargetRole(payload.targetRole().trim());
        entity.setCity(payload.city());
        entity.setPhone(payload.phone());
        entity.setEmail(payload.email());
        entity.setEducation(payload.education());
        entity.setSchool(payload.school());
        entity.setMajor(payload.major());
        entity.setGraduationYear(payload.graduationYear());
        entity.setSkillsJson(jsonCodec.write(payload.skills() == null ? List.of() : payload.skills()));
        entity.setProjectsJson(jsonCodec.write(payload.projects() == null ? List.of() : payload.projects()));
        entity.setExperiencesJson(jsonCodec.write(payload.experiences() == null ? List.of() : payload.experiences()));
        entity.setSelfEvaluation(payload.selfEvaluation());
        entity.setRawText(payload.rawText());
    }
}