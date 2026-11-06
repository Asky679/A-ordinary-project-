package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeView;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 把结构化简历渲染为可投递的 A4 PDF，不依赖前端截图。 */
@Service
public class ResumePdfService {
    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float MARGIN_X = 48;
    private static final float MARGIN_TOP = 48;
    private static final float MARGIN_BOTTOM = 42;
    private static final float CONTENT_W = W - MARGIN_X * 2;

    private static final String[] HEADING_FONTS = {
            "C:/Windows/Fonts/simhei.ttf", "C:/Windows/Fonts/Deng.ttf"
    };
    private static final String[] BODY_FONTS = {
            "C:/Windows/Fonts/Deng.ttf", "C:/Windows/Fonts/simhei.ttf"
    };

    public byte[] render(ResumeView resume) {
        if (resume == null) throw new BusinessException("简历不存在，无法导出 PDF");
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font heading = loadFont(document, HEADING_FONTS);
            PDType0Font body = loadFont(document, BODY_FONTS);
            Cursor cursor = new Cursor(document, heading, body, resume.ownerName());
            cursor.header(resume);
            cursor.education(resume);
            cursor.skills(resume);
            cursor.projects(resume.projects());
            cursor.experiences(resume.experiences());
            cursor.summary(resume.selfEvaluation());
            cursor.finishWithPageNumbers();

            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(nullSafe(resume.ownerName()) + " - " + nullSafe(resume.targetRole()) + " 简历");
            info.setAuthor(nullSafe(resume.ownerName()));
            info.setSubject("CareerNav 简历仓库导出");
            info.setCreator("CareerNav");

            document.save(output);
            return output.toByteArray();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("简历 PDF 生成失败：" + exception.getMessage());
        }
    }

    private PDType0Font loadFont(PDDocument document, String[] candidates) {
        for (String path : candidates) {
            File file = new File(path);
            if (!file.exists()) continue;
            try {
                return PDType0Font.load(document, file);
            } catch (Exception ignored) {
                // 继续尝试下一个中文字体
            }
        }
        throw new BusinessException("系统缺少可用的中文字体（simhei.ttf / Deng.ttf），无法生成简历 PDF");
    }

    private static String nullSafe(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static final class Cursor {
        private static final float DARK_R = 0.055f, DARK_G = 0.18f, DARK_B = 0.18f;
        private static final float TEAL_R = 0.07f, TEAL_G = 0.42f, TEAL_B = 0.39f;
        private static final float CYAN_R = 0.17f, CYAN_G = 0.72f, CYAN_B = 0.68f;
        private static final float GRAY_R = 0.35f, GRAY_G = 0.44f, GRAY_B = 0.43f;

        private final PDDocument document;
        private final PDType0Font headingFont;
        private final PDType0Font bodyFont;
        private final String ownerName;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPage page;
        private PDPageContentStream content;
        private float y;

        private Cursor(PDDocument document, PDType0Font headingFont, PDType0Font bodyFont, String ownerName) {
            this.document = document;
            this.headingFont = headingFont;
            this.bodyFont = bodyFont;
            this.ownerName = nullSafe(ownerName);
            newPage();
        }

        private void newPage() {
            try {
                if (content != null) content.close();
                page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pages.add(page);
                content = new PDPageContentStream(document, page);
                y = H - MARGIN_TOP;
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 分页失败：" + exception.getMessage());
            }
        }

        private void ensure(float height) {
            if (y - height < MARGIN_BOTTOM) {
                newPage();
                drawContinuationHeader();
            }
        }

        private void drawContinuationHeader() {
            try {
                drawText(bodyFont, 8, MARGIN_X, H - MARGIN_TOP + 8, GRAY_R, GRAY_G, GRAY_B,
                        ownerName + " · 简历（续）");
                content.setStrokingColor(0.82f, 0.90f, 0.88f);
                content.setLineWidth(0.6f);
                content.moveTo(MARGIN_X, H - MARGIN_TOP);
                content.lineTo(W - MARGIN_X, H - MARGIN_TOP);
                content.stroke();
                y = H - MARGIN_TOP - 16;
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 页眉生成失败：" + exception.getMessage());
            }
        }

        private void header(ResumeView resume) {
            try {
                float top = y;
                content.setNonStrokingColor(CYAN_R, CYAN_G, CYAN_B);
                content.addRect(MARGIN_X, top - 63, 3.5f, 63);
                content.fill();

                drawText(headingFont, 23, MARGIN_X + 13, top - 28, DARK_R, DARK_G, DARK_B,
                        nullSafe(resume.ownerName()));
                drawText(headingFont, 10.5f, MARGIN_X + 13, top - 47, TEAL_R, TEAL_G, TEAL_B,
                        nullSafe(resume.targetRole()));

                List<String> contacts = new ArrayList<>();
                if (!nullSafe(resume.city()).isBlank()) contacts.add(nullSafe(resume.city()));
                if (!nullSafe(resume.phone()).isBlank()) contacts.add(nullSafe(resume.phone()));
                if (!nullSafe(resume.email()).isBlank()) contacts.add(nullSafe(resume.email()));
                if (!contacts.isEmpty()) {
                    drawText(bodyFont, 8.5f, MARGIN_X + 13, top - 62, GRAY_R, GRAY_G, GRAY_B,
                            String.join("   |   ", contacts));
                }

                y = top - 78;
                content.setStrokingColor(0.82f, 0.90f, 0.88f);
                content.setLineWidth(0.8f);
                content.moveTo(MARGIN_X, y);
                content.lineTo(W - MARGIN_X, y);
                content.stroke();
                y -= 12;
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 页眉生成失败：" + exception.getMessage());
            }
        }

        private void education(ResumeView resume) {
            List<String> rows = new ArrayList<>();
            if (!nullSafe(resume.education()).isBlank() || !nullSafe(resume.school()).isBlank()) {
                String school = nullSafe(resume.school());
                String degree = nullSafe(resume.education());
                String major = nullSafe(resume.major());
                String year = resume.graduationYear() == null ? "" : resume.graduationYear() + " 届";
                List<String> parts = new ArrayList<>();
                if (!school.isBlank()) parts.add(school);
                if (!degree.isBlank()) parts.add(degree);
                if (!major.isBlank()) parts.add(major);
                if (!year.isBlank()) parts.add(year);
                rows.add(String.join("  ·  ", parts));
                List<String> details = new ArrayList<>();
                if (!nullSafe(resume.studyMode()).isBlank()) details.add(nullSafe(resume.studyMode()));
                if (!nullSafe(resume.duration()).isBlank()) details.add("学制 " + nullSafe(resume.duration()));
                if (!nullSafe(resume.college()).isBlank()) details.add(nullSafe(resume.college()));
                if (!nullSafe(resume.department()).isBlank()) details.add(nullSafe(resume.department()));
                if (!nullSafe(resume.studentStatus()).isBlank()) details.add(nullSafe(resume.studentStatus()));
                if (!nullSafe(resume.enrollmentDate()).isBlank()) details.add("入学 " + nullSafe(resume.enrollmentDate()));
                if (!nullSafe(resume.expectedGraduationDate()).isBlank()) details.add("预计毕业 " + nullSafe(resume.expectedGraduationDate()));
                if (!details.isEmpty()) rows.add(String.join("  ·  ", details));
            }
            if (rows.isEmpty()) return;
            section("教育背景");
            for (String row : rows) {
                paragraph(row, 9.2f, 14, DARK_R, DARK_G, DARK_B);
            }
        }

        private void skills(ResumeView resume) {
            if (resume.skills() == null || resume.skills().isEmpty()) return;
            section("专业技能");
            paragraph(String.join("   ·   ", resume.skills()), 9.2f, 14, DARK_R, DARK_G, DARK_B);
        }

        private void projects(List<ProjectItem> projects) {
            if (projects == null || projects.isEmpty()) return;
            section("项目经历");
            for (ProjectItem project : projects) {
                if (project == null) continue;
                ensure(36);
                String title = nullSafe(project.name());
                String role = nullSafe(project.role());
                String meta = role.isBlank() ? "" : role;
                itemHeading(title, meta);
                String tech = nullSafe(project.techStack());
                if (!tech.isBlank()) paragraph("技术栈：" + tech, 8.7f, 13, TEAL_R, TEAL_G, TEAL_B);
                paragraph(project.description(), 9.0f, 14, GRAY_R, GRAY_G, GRAY_B);
                if (!nullSafe(project.highlight()).isBlank()) {
                    paragraph("成果：" + nullSafe(project.highlight()), 9.0f, 14, DARK_R, DARK_G, DARK_B);
                }
                y -= 7;
            }
        }

        private void experiences(List<ExperienceItem> experiences) {
            if (experiences == null || experiences.isEmpty()) return;
            section("实习 / 工作经历");
            for (ExperienceItem experience : experiences) {
                if (experience == null) continue;
                ensure(36);
                itemHeading(nullSafe(experience.company()),
                        nullSafe(experience.position()) + (nullSafe(experience.period()).isBlank() ? "" : "  |  " + nullSafe(experience.period())));
                paragraph(experience.description(), 9.0f, 14, GRAY_R, GRAY_G, GRAY_B);
                if (!nullSafe(experience.achievement()).isBlank()) {
                    paragraph("成果：" + nullSafe(experience.achievement()), 9.0f, 14, DARK_R, DARK_G, DARK_B);
                }
                y -= 7;
            }
        }

        private void summary(String summary) {
            if (nullSafe(summary).isBlank()) return;
            section("个人总结");
            paragraph(summary, 9.2f, 15, GRAY_R, GRAY_G, GRAY_B);
        }

        private void section(String title) {
            ensure(39);
            try {
                y -= 6;
                content.setNonStrokingColor(CYAN_R, CYAN_G, CYAN_B);
                content.addRect(MARGIN_X, y - 1, 3, 13);
                content.fill();
                drawText(headingFont, 10.8f, MARGIN_X + 10, y, TEAL_R, TEAL_G, TEAL_B, title);
                content.setStrokingColor(0.82f, 0.90f, 0.88f);
                content.setLineWidth(0.65f);
                content.moveTo(MARGIN_X + 10, y - 7);
                content.lineTo(W - MARGIN_X, y - 7);
                content.stroke();
                y -= 21;
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 栏目标题生成失败：" + exception.getMessage());
            }
        }

        private void itemHeading(String title, String meta) {
            ensure(22);
            try {
                drawText(headingFont, 9.8f, MARGIN_X, y, DARK_R, DARK_G, DARK_B, title);
                if (!nullSafe(meta).isBlank()) {
                    float width = textWidth(bodyFont, 8.4f, meta);
                    drawText(bodyFont, 8.4f, W - MARGIN_X - width, y, GRAY_R, GRAY_G, GRAY_B, meta);
                }
                y -= 15;
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 经历标题生成失败：" + exception.getMessage());
            }
        }

        private void paragraph(String text, float size, float leading, float r, float g, float b) {
            if (nullSafe(text).isBlank()) return;
            List<String> lines = wrap(bodyFont, size, text, CONTENT_W);
            for (String line : lines) {
                ensure(leading);
                try {
                    drawText(bodyFont, size, MARGIN_X, y, r, g, b, line);
                    y -= leading;
                } catch (IOException exception) {
                    throw new BusinessException("简历 PDF 正文生成失败：" + exception.getMessage());
                }
            }
        }

        private void drawText(PDType0Font font, float size, float x, float y,
                              float r, float g, float b, String text) throws IOException {
            if (nullSafe(text).isBlank()) return;
            content.beginText();
            content.setFont(font, size);
            content.setNonStrokingColor(r, g, b);
            content.newLineAtOffset(x, y);
            content.showText(sanitize(text));
            content.endText();
        }

        private List<String> wrap(PDType0Font font, float size, String text, float maxWidth) {
            List<String> lines = new ArrayList<>();
            String value = sanitize(text).replace('\r', '\n');
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < value.length(); index++) {
                char ch = value.charAt(index);
                if (ch == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
                try {
                    if (textWidth(font, size, current.toString()) > maxWidth) {
                        int cut = current.length() - 1;
                        int space = current.lastIndexOf(" ");
                        if (space > current.length() * 0.55) cut = space;
                        lines.add(current.substring(0, cut).trim());
                        String remainder = current.substring(cut).trim();
                        current.setLength(0);
                        current.append(remainder);
                    }
                } catch (IOException exception) {
                    throw new BusinessException("简历文字宽度计算失败：" + exception.getMessage());
                }
            }
            if (current.length() > 0) lines.add(current.toString());
            return lines;
        }

        private float textWidth(PDType0Font font, float size, String text) throws IOException {
            return font.getStringWidth(sanitize(text)) / 1000f * size;
        }

        private String sanitize(String text) {
            if (text == null) return "";
            StringBuilder result = new StringBuilder(text.length());
            for (int index = 0; index < text.length(); index++) {
                char ch = text.charAt(index);
                if (ch == '\n' || ch == '\t' || ch >= 32) result.append(ch == '\t' ? ' ' : ch);
            }
            return result.toString();
        }

        private void finishWithPageNumbers() {
            try {
                if (content != null) {
                    content.close();
                    content = null;
                }
                int total = pages.size();
                for (int index = 0; index < total; index++) {
                    try (PDPageContentStream footer = new PDPageContentStream(
                            document, pages.get(index), PDPageContentStream.AppendMode.APPEND, true, true)) {
                        footer.beginText();
                        footer.setFont(bodyFont, 7.5f);
                        footer.setNonStrokingColor(0.52f, 0.61f, 0.59f);
                        footer.newLineAtOffset(MARGIN_X, 22);
                        footer.showText("CareerNav · 简历仓库导出    " + (index + 1) + " / " + total);
                        footer.endText();
                    }
                }
            } catch (IOException exception) {
                throw new BusinessException("简历 PDF 页脚生成失败：" + exception.getMessage());
            }
        }
    }
}