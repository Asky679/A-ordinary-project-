package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.*;import com.careernav.service.LearningPathService;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/learning-paths")
public class LearningPathController {private final LearningPathService service;public LearningPathController(LearningPathService service){this.service=service;}@PostMapping public ApiResponse<LearningPath> generate(@Valid @RequestBody LearningPathRequest request){return ApiResponse.ok("学习路线已生成",service.generate(request.resumeId(),request.targetRole()));}}
