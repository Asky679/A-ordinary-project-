package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.ResumeImportResult;
import com.careernav.service.ResumeImportService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resumes/import")
public class ResumeImportController {
    private final ResumeImportService service;

    public ResumeImportController(ResumeImportService service) {
        this.service = service;
    }

    /**
     * PDF 文本层优先由后端读取；扫描件和图片使用浏览器 OCR 结果。
     * 接口只返回草稿，不自动保存。
     */
    @PostMapping
    public ApiResponse<ResumeImportResult> importResume(@RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "extractedText", required = false) String extractedText,
                                                        @RequestParam(value = "extractionMethod", required = false) String extractionMethod) {
        return ApiResponse.ok("简历内容已解析，请核对后填入编辑器", service.importResume(file, extractedText, extractionMethod));
    }
}
