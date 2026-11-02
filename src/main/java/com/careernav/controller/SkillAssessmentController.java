package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.SkillProficiencyView;
import com.careernav.domain.ApiModels.SkillTestResult;
import com.careernav.domain.ApiModels.SkillTestStartRequest;
import com.careernav.domain.ApiModels.SkillTestSubmitRequest;
import com.careernav.domain.ApiModels.SkillTestView;
import com.careernav.domain.ApiModels.SkillRoundResult;
import com.careernav.domain.ApiModels.SkillRoundSubmitRequest;
import com.careernav.domain.ApiModels.SkillRoundView;
import com.careernav.service.SkillAssessmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills/tests")
public class SkillAssessmentController {
    private final SkillAssessmentService service;

    public SkillAssessmentController(SkillAssessmentService service) {
        this.service = service;
    }

    @PostMapping("/java")
    public ApiResponse<SkillTestView> startJava(@Valid @RequestBody SkillTestStartRequest request) {
        return ApiResponse.ok("Java 技能测试已创建", service.startJavaTest(request.resumeId()));
    }

    @PostMapping("/java/multi-round")
    public ApiResponse<SkillRoundView> startJavaMultiRound(@Valid @RequestBody SkillTestStartRequest request) {
        return ApiResponse.ok("Java ?????????", service.startJavaMultiRound(request.resumeId()));
    }

    @PostMapping("/{sessionId}/rounds/{roundNo}/submit")
    public ApiResponse<SkillRoundResult> submitRound(@PathVariable Long sessionId,
                                                     @PathVariable int roundNo,
                                                     @RequestBody SkillRoundSubmitRequest request) {
        return ApiResponse.ok("?????????", service.submitJavaRound(sessionId, roundNo, request));
    }

    @PostMapping("/{sessionId}/submit")
    public ApiResponse<SkillTestResult> submit(@PathVariable Long sessionId,
                                               @RequestBody SkillTestSubmitRequest request) {
        return ApiResponse.ok("技能测试已完成", service.submit(sessionId, request));
    }

    @GetMapping("/proficiency")
    public ApiResponse<SkillProficiencyView> proficiency(@RequestParam Long resumeId,
                                                          @RequestParam(defaultValue = "Java") String skill) {
        return ApiResponse.ok(service.proficiency(resumeId, skill));
    }
}