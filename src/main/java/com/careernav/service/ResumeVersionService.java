package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import com.careernav.entity.ResumeEntity;
import com.careernav.entity.ResumeVersionEntity;
import com.careernav.repository.ResumeRepository;
import com.careernav.repository.ResumeVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 简历版本管理 + 事实保护。
 *
 * <p>版本快照保存的是「创建那一刻的完整简历」，并记录目标岗位、当时的匹配分和事实锁，
 * 因此历史版本不受后续修改影响。恢复版本属于用户显式操作，会跳过事实锁校验。
 */
@Service
public class ResumeVersionService {
    private static final int MAX_LABEL = 40;

    private final ResumeRepository resumes;
    private final ResumeVersionRepository versions;
    private final ResumeService resumeService;
    private final JobService jobs;
    private final JsonCodec jsonCodec;

    public ResumeVersionService(ResumeRepository resumes, ResumeVersionRepository versions,
                                ResumeService resumeService, JobService jobs, JsonCodec jsonCodec) {
        this.resumes = resumes;
        this.versions = versions;
        this.resumeService = resumeService;
        this.jobs = jobs;
        this.jsonCodec = jsonCodec;
    }

    // ---------------------------------------------------------------- 版本
    @Transactional
    public ResumeVersionView create(Long resumeId, ResumeVersionRequest request) {
        ResumeView view = resumeService.get(resumeId);
        ResumeEntity entity = resumeService.getEntity(resumeId);

        List<ResumeVersionEntity> existing = versions.findByResumeIdOrderByVersionNoDesc(resumeId);
        int next = existing.isEmpty() ? 1 : existing.get(0).getVersionNo() + 1;

        String jobTitle = null;
        Integer score = null;
        if (request != null && request.targetJobId() != null) {
            try {
                JobRecommendation match = jobs.evaluate(view, request.targetJobId());
                score = match.matchScore();
                jobTitle = match.job().title();
            } catch (Exception ignored) {
                // 岗位不存在时只存 id，不影响版本创建
            }
        }

        ResumeVersionEntity version = new ResumeVersionEntity();
        version.setResumeId(resumeId);
        version.setVersionNo(next);
        version.setLabel(clip(clean(request == null ? null : request.label()), MAX_LABEL, "未命名版本"));
        version.setTargetJobId(request == null ? null : request.targetJobId());
        version.setTargetJobTitle(jobTitle);
        version.setMatchScore(score);
        version.setSnapshotJson(jsonCodec.write(toPayload(view)));
        version.setFactLocksJson(entity.getFactLocksJson() == null ? "[]" : entity.getFactLocksJson());
        version.setNote(clip(clean(request == null ? null : request.note()), 500, null));
        version.setCreatedAt(LocalDateTime.now());
        return toView(versions.save(version));
    }

    @Transactional(readOnly = true)
    public List<ResumeVersionView> list(Long resumeId) {
        resumeService.get(resumeId); // 校验简历存在
        return versions.findByResumeIdOrderByVersionNoDesc(resumeId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ResumeVersionView get(Long resumeId, Long versionId) {
        return toView(find(resumeId, versionId));
    }

    @Transactional
    public ResumeView restore(Long resumeId, Long versionId) {
        ResumeVersionEntity version = find(resumeId, versionId);
        ResumePayload snapshot = jsonCodec.read(version.getSnapshotJson(), ResumePayload.class);
        if (snapshot == null) throw new BusinessException("版本快照为空，无法恢复");
        return resumeService.applyWithoutLockCheck(resumeId, snapshot);
    }

    @Transactional(readOnly = true)
    public ResumeVersionCompare compare(Long resumeId, Long leftId, Long rightId) {
        ResumeVersionView left = get(resumeId, leftId);
        ResumeVersionView right = get(resumeId, rightId);
        ResumePayload a = left.snapshot();
        ResumePayload b = right.snapshot();
        List<ResumeFieldDiff> diffs = new ArrayList<>();
        diff(diffs, "ownerName", "姓名", a.ownerName(), b.ownerName());
        diff(diffs, "targetRole", "目标岗位", a.targetRole(), b.targetRole());
        diff(diffs, "city", "城市", a.city(), b.city());
        diff(diffs, "phone", "手机号", a.phone(), b.phone());
        diff(diffs, "email", "邮箱", a.email(), b.email());
        diff(diffs, "education", "学历", a.education(), b.education());
        diff(diffs, "school", "学校", a.school(), b.school());
        diff(diffs, "major", "专业", a.major(), b.major());
        diff(diffs, "graduationYear", "毕业年份", str(a.graduationYear()), str(b.graduationYear()));
        diff(diffs, "skills", "技能", jsonCodec.write(a.skills()), jsonCodec.write(b.skills()));
        diff(diffs, "projects", "项目经历", jsonCodec.write(a.projects()), jsonCodec.write(b.projects()));
        diff(diffs, "experiences", "实习/工作经历", jsonCodec.write(a.experiences()), jsonCodec.write(b.experiences()));
        diff(diffs, "selfEvaluation", "自我评价", a.selfEvaluation(), b.selfEvaluation());
        return new ResumeVersionCompare(left, right, diffs);
    }

    // ---------------------------------------------------------------- 事实锁
    @Transactional
    public List<String> setFactLocks(Long resumeId, List<String> fields) {
        ResumeEntity entity = resumeService.getEntity(resumeId);
        List<String> safe = fields == null ? List.of() : fields.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        entity.setFactLocksJson(jsonCodec.write(safe));
        entity.setUpdatedAt(LocalDateTime.now());
        resumes.save(entity);
        return safe;
    }

    @Transactional(readOnly = true)
    public List<String> getFactLocks(Long resumeId) {
        return jsonCodec.readList(resumeService.getEntity(resumeId).getFactLocksJson(), String.class);
    }

    // ---------------------------------------------------------------- 内部
    private ResumeVersionEntity find(Long resumeId, Long versionId) {
        return versions.findByIdAndResumeId(versionId, resumeId)
                .orElseThrow(() -> new BusinessException("版本不存在：" + versionId));
    }

    private ResumeVersionView toView(ResumeVersionEntity e) {
        return new ResumeVersionView(e.getId(), e.getResumeId(), e.getVersionNo(), e.getLabel(),
                e.getTargetJobId(), e.getTargetJobTitle(), e.getMatchScore(), e.getNote(),
                jsonCodec.readList(e.getFactLocksJson(), String.class), e.getCreatedAt(),
                jsonCodec.read(e.getSnapshotJson(), ResumePayload.class));
    }

    private ResumePayload toPayload(ResumeView v) {
        return new ResumePayload(v.ownerName(), v.targetRole(), v.city(), v.phone(), v.email(),
                v.education(), v.school(), v.major(), v.graduationYear(), v.skills(),
                v.projects(), v.experiences(), v.selfEvaluation(), v.rawText());
    }

    private void diff(List<ResumeFieldDiff> out, String field, String label, String left, String right) {
        if (!Objects.equals(left, right)) out.add(new ResumeFieldDiff(field, label, left, right));
    }

    private String str(Integer value) { return value == null ? null : String.valueOf(value); }

    private String clean(String value) { return value == null ? "" : value.trim(); }

    private String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
