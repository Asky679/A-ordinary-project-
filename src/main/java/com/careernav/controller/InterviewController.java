package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.*;import com.careernav.service.InterviewService;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import java.util.List;
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
    private final InterviewService service;

    public InterviewController(InterviewService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<InterviewView>> list() { return ApiResponse.ok(service.list()); }

    @GetMapping("/evidence")
    public ApiResponse<List<InterviewSkillEvidence>> evidence(@RequestParam Long resumeId,
                                                               @RequestParam(required = false) Long jobId) {
        return ApiResponse.ok(service.latestSkillEvidence(resumeId, jobId));
    }

    /** 预览所选岗位的 JD 面试蓝图，不创建面试会话。 */
    @GetMapping("/blueprint")
    public ApiResponse<JobInterviewBlueprint> blueprint(@RequestParam Long jobId) {
        return ApiResponse.ok(service.blueprint(jobId));
    }

    @GetMapping("/{id}")
    public ApiResponse<InterviewView> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }

    @PostMapping
    public ApiResponse<InterviewView> start(@Valid @RequestBody InterviewStartRequest request) {
        return ApiResponse.ok("模拟面试已创建", service.start(request));
    }

    @PostMapping("/{id}/answers")
    public ApiResponse<InterviewView> answer(@PathVariable Long id,
                                             @Valid @RequestBody InterviewAnswerRequest request) {
        return ApiResponse.ok(service.answer(id, request.answer()));
    }
}
