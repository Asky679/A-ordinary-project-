package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.EducationVerificationResult;
import com.careernav.service.EducationVerificationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resumes")
public class EducationVerificationController {
    private final EducationVerificationService service;

    public EducationVerificationController(EducationVerificationService service) {
        this.service = service;
    }

    /**
     * 用户主动上传学信网报告或学历证书后，由 Agent 解析并与简历教育背景比对。
     * ocrText 仅供图片使用，由浏览器在本地 OCR 后传入；服务端不会登录学信网。
     */
    @PostMapping(value = "/{id}/education-verification", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<EducationVerificationResult> verify(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "ocrText", required = false) String ocrText) {
        return ApiResponse.ok("学历材料一致性核验完成", service.verify(id, file, ocrText));
    }
}