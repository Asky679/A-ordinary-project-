package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.*;
import com.careernav.service.ResumeService;
import com.careernav.service.ResumeVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService service;
    private final ResumeVersionService versions;

    public ResumeController(ResumeService service, ResumeVersionService versions) {
        this.service = service;
        this.versions = versions;
    }

    // ---------------------------------------------------------------- 简历
    @GetMapping
    public ApiResponse<List<ResumeView>> list() {
        return ApiResponse.ok(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeView> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<ResumeView> create(@Valid @RequestBody ResumePayload payload) {
        return ApiResponse.ok("简历已保存", service.create(payload));
    }

    /** force=true 时忽略事实锁（用于用户明确确认要修改已锁定事实的场景）。 */
    @PutMapping("/{id}")
    public ApiResponse<ResumeView> update(@PathVariable Long id,
                                          @Valid @RequestBody ResumePayload payload,
                                          @RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok("简历已更新", service.update(id, payload, force));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("简历已删除", null);
    }

    // ---------------------------------------------------------------- 版本管理
    @GetMapping("/{id}/versions")
    public ApiResponse<List<ResumeVersionView>> listVersions(@PathVariable Long id) {
        return ApiResponse.ok(versions.list(id));
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<ResumeVersionView> createVersion(@PathVariable Long id,
                                                        @RequestBody(required = false) ResumeVersionRequest request) {
        return ApiResponse.ok("版本已保存", versions.create(id, request));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ApiResponse<ResumeVersionView> getVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return ApiResponse.ok(versions.get(id, versionId));
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public ApiResponse<ResumeView> restoreVersion(@PathVariable Long id, @PathVariable Long versionId) {
        return ApiResponse.ok("已恢复到该版本", versions.restore(id, versionId));
    }

    @GetMapping("/{id}/versions/compare")
    public ApiResponse<ResumeVersionCompare> compareVersions(@PathVariable Long id,
                                                             @RequestParam Long left,
                                                             @RequestParam Long right) {
        return ApiResponse.ok(versions.compare(id, left, right));
    }

    // ---------------------------------------------------------------- 事实锁
    @GetMapping("/{id}/fact-locks")
    public ApiResponse<List<String>> getFactLocks(@PathVariable Long id) {
        return ApiResponse.ok(versions.getFactLocks(id));
    }

    @PutMapping("/{id}/fact-locks")
    public ApiResponse<List<String>> setFactLocks(@PathVariable Long id,
                                                  @RequestBody FactLockRequest request) {
        return ApiResponse.ok("事实锁已更新", versions.setFactLocks(id, request.fields()));
    }
}
