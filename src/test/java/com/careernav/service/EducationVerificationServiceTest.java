package com.careernav.service;

import com.careernav.domain.ApiModels.EducationVerificationResult;
import com.careernav.entity.ResumeEntity;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EducationVerificationServiceTest {
    @Test
    void comparesOfficialReportFieldsWithResume() throws Exception {
        File fontFile = new File("C:/Windows/Fonts/simhei.ttf");
        if (!fontFile.exists()) return;

        ResumeEntity resume = new ResumeEntity();
        resume.setOwnerName("Ada");
        resume.setSchool("Example University");
        resume.setEducation("本科");
        resume.setMajor("Computer Science");
        resume.setGraduationYear(2026);

        ResumeService resumeService = mock(ResumeService.class);
        when(resumeService.getEntity(1L)).thenReturn(resume);
        AgentModelClient agent = mock(AgentModelClient.class);
        when(agent.generateJson(anyString(), any())).thenReturn(null);
        EducationVerificationService service = new EducationVerificationService(resumeService, agent);

        byte[] pdf = reportPdf(fontFile);
        MockMultipartFile file = new MockMultipartFile("file", "education-report.pdf", "application/pdf", pdf);
        EducationVerificationResult result = service.verify(1L, file, null);

        assertEquals("CONSISTENT", result.status());
        assertTrue(result.officialReportDetected());
        assertEquals(100, result.score());
        assertTrue(result.checks().stream().allMatch(item -> "MATCH".equals(item.status())));
        assertEquals("RULE_FALLBACK", result.extractionMode());
    }

    @Test
    void reportsMismatchWhenSchoolDiffers() throws Exception {
        File fontFile = new File("C:/Windows/Fonts/simhei.ttf");
        if (!fontFile.exists()) return;

        ResumeEntity resume = new ResumeEntity();
        resume.setOwnerName("Ada");
        resume.setSchool("Another University");
        resume.setEducation("本科");
        resume.setMajor("Computer Science");
        resume.setGraduationYear(2026);

        ResumeService resumeService = mock(ResumeService.class);
        when(resumeService.getEntity(1L)).thenReturn(resume);
        AgentModelClient agent = mock(AgentModelClient.class);
        when(agent.generateJson(anyString(), any())).thenReturn(null);
        EducationVerificationService service = new EducationVerificationService(resumeService, agent);

        MockMultipartFile file = new MockMultipartFile(
                "file", "education-report.pdf", "application/pdf", reportPdf(fontFile));
        EducationVerificationResult result = service.verify(1L, file, null);

        assertEquals("MISMATCH", result.status());
        assertTrue(result.checks().stream().anyMatch(item ->
                "school".equals(item.key()) && "MISMATCH".equals(item.status())));
    }
    private byte[] reportPdf(File fontFile) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType0Font font = PDType0Font.load(document, fontFile);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(50, 760);
                content.setLeading(20);
                for (String line : List.of(
                        "教育部学历证书电子注册备案表",
                        "姓名: Ada",
                        "学校: Example University",
                        "学历层次: 本科",
                        "专业: Computer Science",
                        "毕业时间: 2026",
                        "在线验证报告")) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}