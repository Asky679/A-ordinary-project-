package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.*;
import com.careernav.service.ResumeGuidanceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/resume-guidance")
public class ResumeGuidanceController {
    private final ResumeGuidanceService service;

    public ResumeGuidanceController(ResumeGuidanceService service) {
        this.service = service;
    }

    /** 可选方向列表（来自岗位表 category）。 */
    @GetMapping("/directions")
    public ApiResponse<List<String>> directions() {
        return ApiResponse.ok(service.directions());
    }

    /** 方向画像 + 技能覆盖 + 证据检查 + 质量完成度 + 项目引导问题。 */
    @GetMapping("/{resumeId}")
    public ApiResponse<GuidanceReport> analyze(@PathVariable Long resumeId,
                                               @RequestParam(defaultValue = "") String role) {
        return ApiResponse.ok(service.analyze(resumeId, role));
    }

    /** 根据项目五问生成描述草稿（可选 AI 润色，禁止增加事实）。 */
    @PostMapping("/draft")
    public ApiResponse<ProjectDraft> draft(@RequestBody ProjectDraftRequest request) {
        return ApiResponse.ok("项目描述已生成", service.draft(request));
    }
}
