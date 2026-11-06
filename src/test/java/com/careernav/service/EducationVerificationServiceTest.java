package com.careernav.service;

import com.careernav.domain.ApiModels.EducationVerificationResult;
import com.careernav.entity.EducationVerificationEntity;
import com.careernav.entity.ResumeEntity;
import com.careernav.repository.EducationVerificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        EducationVerificationRepository repository = mock(EducationVerificationRepository.class);
        when(repository.findByResumeId(1L)).thenReturn(Optional.empty());
        QrCodeService qrCodeService = mock(QrCodeService.class);
        when(qrCodeService.decode(any(), anyString())).thenReturn(new QrCodeService.QrResult(
                true, "OFFICIAL_QR", "已识别学信网官方二维码", "#TESTCODE123#", null, "TESTCODE123",
                "https://www.chsi.com.cn/xlcx/yzzw.jsp", null, 1));
        AgentMatchCacheService matchCache = mock(AgentMatchCacheService.class);
        EducationVerificationService service = new EducationVerificationService(
                resumeService, agent, repository, new JsonCodec(new ObjectMapper()), qrCodeService, matchCache);

        byte[] pdf = reportPdf(fontFile);
        MockMultipartFile file = new MockMultipartFile("file", "education-report.pdf", "application/pdf", pdf);
        EducationVerificationResult result = service.verify(1L, file, null);

        assertEquals("CONSISTENT", result.status());
        assertTrue(result.passed());
        assertTrue(result.officialReportDetected());
        assertEquals(100, result.score());
        assertTrue(result.checks().stream().filter(item -> !"qrCode".equals(item.key()))
                .allMatch(item -> "MATCH".equals(item.status())));
        assertEquals("RULE_FALLBACK", result.extractionMode());
    }

    @Test
    void cancelDeletesVerificationAndInvalidatesMatchCache() {
        ResumeService resumeService = mock(ResumeService.class);
        when(resumeService.getEntity(7L)).thenReturn(new ResumeEntity());
        EducationVerificationRepository repository = mock(EducationVerificationRepository.class);
        AgentMatchCacheService matchCache = mock(AgentMatchCacheService.class);
        EducationVerificationService service = new EducationVerificationService(
                resumeService, mock(AgentModelClient.class), repository,
                new JsonCodec(new ObjectMapper()), mock(QrCodeService.class), matchCache);

        service.cancel(7L);

        verify(repository).deleteByResumeId(7L);
        verify(matchCache).invalidateResume(7L);
    }

    @Test
    void officialQrPassesEvenWhenSchoolDiffers() throws Exception {
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
        EducationVerificationRepository repository = mock(EducationVerificationRepository.class);
        when(repository.findByResumeId(1L)).thenReturn(Optional.empty());
        QrCodeService qrCodeService = mock(QrCodeService.class);
        when(qrCodeService.decode(any(), anyString())).thenReturn(new QrCodeService.QrResult(
                true, "OFFICIAL_QR", "已识别学信网官方二维码", "#TESTCODE123#", null, "TESTCODE123",
                "https://www.chsi.com.cn/xlcx/yzzw.jsp", null, 1));
        AgentMatchCacheService matchCache = mock(AgentMatchCacheService.class);
        EducationVerificationService service = new EducationVerificationService(
                resumeService, agent, repository, new JsonCodec(new ObjectMapper()), qrCodeService, matchCache);

        MockMultipartFile file = new MockMultipartFile(
                "file", "education-report.pdf", "application/pdf", reportPdf(fontFile));
        EducationVerificationResult result = service.verify(1L, file, null);

        assertEquals("CONSISTENT", result.status());
        assertEquals("二维码核验通过", result.statusLabel());
        assertTrue(result.passed());
        assertEquals(100, result.score());
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