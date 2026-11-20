package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.ProjectClueAnalysis;
import com.careernav.domain.ApiModels.ProjectClueRequest;
import com.careernav.service.ProjectClueAnalysisService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectClueController {
    private final ProjectClueAnalysisService service;

    public ProjectClueController(ProjectClueAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/analyze-clues")
    public ApiResponse<ProjectClueAnalysis> analyze(@RequestBody ProjectClueRequest request) {
        return ApiResponse.ok(service.analyze(request));
    }
}