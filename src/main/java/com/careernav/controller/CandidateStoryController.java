package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.CandidateStoryView;
import com.careernav.service.CandidateStoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stories")
public class CandidateStoryController {
    private final CandidateStoryService service;

    public CandidateStoryController(CandidateStoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CandidateStoryView>> list(@RequestParam Long resumeId) {
        return ApiResponse.ok(service.list(resumeId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok("故事已删除", null);
    }

    /** 将历史已完成面试补建成故事库。 */
    @PostMapping("/rebuild")
    public ApiResponse<List<CandidateStoryView>> rebuild(@RequestParam Long resumeId,
                                                          @RequestParam(defaultValue = "6") int limit) {
        return ApiResponse.ok("历史面试已整理", service.rebuildFromHistory(resumeId, limit));
    }

    /** 只整理用户勾选的已完成面试。 */
    @PostMapping("/rebuild-selected")
    public ApiResponse<List<CandidateStoryView>> rebuildSelected(@RequestBody StoryRebuildRequest request) {
        return ApiResponse.ok("选中的面试已整理",
                service.rebuildFromSessions(request.resumeId(), request.sessionIds()));
    }

    public record StoryRebuildRequest(Long resumeId, List<Long> sessionIds) {}

    @GetMapping("/trash")
    public ApiResponse<List<CandidateStoryView>> trash(@RequestParam Long resumeId) {
        return ApiResponse.ok(service.trash(resumeId));
    }

    @DeleteMapping
    public ApiResponse<Integer> clear(@RequestParam Long resumeId) {
        return ApiResponse.ok("\u6545\u4e8b\u5df2\u79fb\u5165\u56de\u6536\u7ad9", service.clear(resumeId));
    }

    @DeleteMapping("/trash")
    public ApiResponse<Integer> emptyTrash(@RequestParam Long resumeId) {
        return ApiResponse.ok("\u56de\u6536\u7ad9\u5df2\u6e05\u7a7a", service.emptyTrash(resumeId));
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id) {
        service.restore(id);
        return ApiResponse.ok("\u6545\u4e8b\u5df2\u6062\u590d", null);
    }

    @DeleteMapping("/{id}/permanent")
    public ApiResponse<Void> permanentDelete(@PathVariable Long id) {
        service.permanentDelete(id);
        return ApiResponse.ok("\u6545\u4e8b\u5df2\u6c38\u4e45\u5220\u9664", null);
    }
}
