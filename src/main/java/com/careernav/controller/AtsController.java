package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.AtsReport;
import com.careernav.service.AtsPdfService;
import com.careernav.service.AtsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ats")
public class AtsController {
    private final AtsService service;
    private final AtsPdfService pdfService;

    public AtsController(AtsService service, AtsPdfService pdfService) {
        this.service = service;
        this.pdfService = pdfService;
    }

    /** 上传简历文件（PDF / DOCX / TXT）做 ATS 兼容性检查，可选绑定目标岗位。 */
    @PostMapping("/check")
    public ApiResponse<AtsReport> check(@RequestParam("file") MultipartFile file,
                                        @RequestParam(required = false) Long jobId) {
        return ApiResponse.ok("ATS 检查完成", service.check(file, jobId));
    }

    /** 把检查结果渲染成一页 PDF 报告。 */
    @PostMapping("/report")
    public ResponseEntity<byte[]> report(@RequestBody AtsReport report) {
        byte[] pdf = pdfService.render(report);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ats-report.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
