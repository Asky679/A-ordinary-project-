package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.service.ProjectQuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.careernav.common.BusinessException;

import java.io.IOException;

@RestController
@RequestMapping("/api/project-questions")
public class ProjectQuestionController {
    private final ProjectQuestionService service;

    public ProjectQuestionController(ProjectQuestionService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ProjectQuestionService.QuestionSet> generate(@Valid @RequestBody GenerateRequest request) {
        return ApiResponse.ok(service.generate(request.repoUrl(), request.projectDescription()));
    }

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<ProjectQuestionService.QuestionSet> upload(@RequestParam("file") MultipartFile file,
                                                                  @RequestParam(value = "projectDescription", required = false) String description) {
        if (file.isEmpty()) throw new BusinessException("请选择项目 ZIP 文件");
        try {
            return ApiResponse.ok(service.generateZip(file.getOriginalFilename(), file.getBytes(), description));
        } catch (IOException exception) {
            throw new BusinessException("读取上传文件失败：" + exception.getMessage());
        }
    }

    @PostMapping("/follow-up")
    public ApiResponse<ProjectQuestionService.FollowUp> followUp(@Valid @RequestBody FollowUpRequest request) {
        return ApiResponse.ok(service.followUp(request.repoUrl(), request.sha(), request.projectDescription(), request.questionId(), request.answer()));
    }

    public record GenerateRequest(@NotBlank(message = "请填写 GitHub 仓库地址") String repoUrl, String projectDescription) { }
    public record FollowUpRequest(@NotBlank String repoUrl, @NotBlank String sha, String projectDescription,
                                  @NotBlank String questionId, @NotBlank String answer) { }
}
