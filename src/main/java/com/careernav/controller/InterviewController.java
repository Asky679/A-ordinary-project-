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

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("历史对话已删除", null);
    }


    @GetMapping("/trash")
    public ApiResponse<List<InterviewView>> trash() {
        return ApiResponse.ok(service.trash());
    }

    @DeleteMapping
    public ApiResponse<Integer> clearAll() {
        return ApiResponse.ok("\u7ec3\u4e60\u8bb0\u5f55\u5df2\u79fb\u5165\u56de\u6536\u7ad9", service.clearAll());
    }

    @DeleteMapping("/trash")
    public ApiResponse<Integer> emptyTrash() {
        return ApiResponse.ok("\u56de\u6536\u7ad9\u5df2\u6e05\u7a7a", service.emptyTrash());
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ApiResponse.ok("\u7ec3\u4e60\u8bb0\u5f55\u5df2\u6062\u590d", null);
    }

    @DeleteMapping("/{id}/permanent")
    public ApiResponse<Void> permanentDelete(@PathVariable Long id) {
        service.permanentDelete(id);
        return ApiResponse.ok("\u7ec3\u4e60\u8bb0\u5f55\u5df2\u6c38\u4e45\u5220\u9664", null);
    }
}
