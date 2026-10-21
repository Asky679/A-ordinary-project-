package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把 ATS 检查结果渲染成一页（必要时多页）PDF 报告。 */
@Service
public class AtsPdfService {
    private static final float W = PDRectangle.A4.getWidth();
    private static final float H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50;
    private static final float CONTENT_W = W - MARGIN * 2;

    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simsun.ttc"
    };

    public byte[] render(AtsReport report) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = loadFont(doc);
            Cursor cursor = new Cursor(doc, font);

            cursor.title("ATS 体检报告");
            cursor.sub("生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    + "    文件类型：" + value(report.parsed(), "fileType")
                    + "    页数：" + value(report.parsed(), "pages"));

            cursor.gap(10);
            cursor.scoreBlock(report);
            cursor.gap(14);

            cursor.section("一、分项得分");
            AtsBreakdown b = report.breakdown();
            cursor.bar("格式可解析性", b.parseability(), 30);
            cursor.bar("结构完整性", b.structure(), 25);
            cursor.bar("关键词匹配", b.keywords(), 30);
            cursor.bar("内容质量", b.content(), 15);

            cursor.gap(10);
            cursor.section("二、简历识别结果");
            Map<String, Object> p = report.parsed();
            cursor.line("文件：" + value(p, "fileType") + "　字符数：" + value(p, "textLength")
                    + "　表格：" + value(p, "tables") + "　图片：" + value(p, "pictures"));
            cursor.line("邮箱：" + (Boolean.TRUE.equals(p.get("hasEmail")) ? "已识别" : "未识别")
                    + "　手机号：" + (Boolean.TRUE.equals(p.get("hasPhone")) ? "已识别" : "未识别"));
            cursor.line("识别到的区块：" + join(p.get("sectionsFound")));
            if (Boolean.TRUE.equals(p.get("keywordEvaluated"))) {
                cursor.line("目标岗位：" + value(p, "jobTitle") + "　关键词覆盖：" + value(p, "keywordCoverage") + "%");
                cursor.line("已命中：" + join(p.get("matchedKeywords")));
                cursor.line("缺失：" + join(p.get("missingKeywords")));
            } else {
                cursor.line("目标岗位：未选择（关键词匹配未评估）");
            }

            cursor.gap(10);
            cursor.section("三、问题与建议");
            List<AtsIssue> issues = report.issues() == null ? List.of() : report.issues();
            if (issues.isEmpty()) {
                cursor.line("没有发现明显问题，可以投递。");
            } else {
                for (AtsIssue issue : issues) {
                    cursor.issue(issue);
                }
            }

            cursor.gap(12);
            cursor.footer("说明：ATS 没有公开的统一算法，不同厂商行为不同。本报告依据单栏纯文本、标准区块、"
                    + "关键词覆盖等公开最佳实践给出风险提示，不代表任何具体 ATS 厂商的真实判定结果。");

            cursor.close();
            doc.save(out);
            return out.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("PDF 生成失败：" + e.getMessage());
        }
    }

    private PDType0Font loadFont(PDDocument doc) {
        for (String path : FONT_CANDIDATES) {
            File file = new File(path);
            if (!file.exists()) continue;
            try {
                return PDType0Font.load(doc, file);
            } catch (Exception ignored) {
                // TTC 需要 TrueTypeCollection，直接跳过，继续找下一个
            }
        }
        throw new BusinessException("系统缺少可用的中文字体（simhei.ttf / Deng.ttf），无法生成 PDF");
    }

    private static String value(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? "-" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static String join(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "无" : String.join("、", list.stream().map(String::valueOf).toList());
        }
        return value == null ? "无" : String.valueOf(value);
    }

    /** 简单的纵向光标 + 自动分页。 */
    private static final class Cursor {
        private final PDDocument doc;
        private final PDType0Font font;
        private PDPageContentStream cs;
        private float y;

        Cursor(PDDocument doc, PDType0Font font) throws Exception {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        private void newPage() throws Exception {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = H - MARGIN;
        }

        private void ensure(float need) throws Exception {
            if (y - need < MARGIN) newPage();
        }

        void title(String text) throws Exception {
            ensure(34);
            write(text, 22, true, MARGIN, y, 0.06f, 0.17f, 0.16f);
            y -= 30;
        }

        void sub(String text) throws Exception {
            write(text, 9, false, MARGIN, y, 0.42f, 0.5f, 0.48f);
            y -= 16;
        }

        void gap(float v) { y -= v; }

        void section(String text) throws Exception {
            ensure(28);
            write(text, 13, true, MARGIN, y, 0.07f, 0.45f, 0.41f);
            y -= 20;
        }

        void line(String text) throws Exception {
            for (String row : wrap(text, 10, CONTENT_W)) {
                ensure(16);
                write(row, 10, false, MARGIN, y, 0.23f, 0.33f, 0.32f);
                y -= 15;
            }
        }

        void scoreBlock(AtsReport report) throws Exception {
            ensure(86);
            float top = y;
            float boxH = 78;
            cs.setNonStrokingColor(0.95f, 0.98f, 0.97f);
            cs.addRect(MARGIN, top - boxH, CONTENT_W, boxH);
            cs.fill();

            String score = String.valueOf(report.atsScore());
            write(score, 34, true, MARGIN + 26, top - 40, 0.07f, 0.45f, 0.41f);
            write("/100", 11, false, MARGIN + 26 + width(score, 34) + 4, top - 40, 0.45f, 0.52f, 0.5f);
            write("ATS 总分", 9, false, MARGIN + 26, top - 58, 0.42f, 0.5f, 0.48f);
            wrap(report.level(), 12, CONTENT_W - 200);
            write(report.level(), 12, true, MARGIN + 170, top - 38, 0.08f, 0.17f, 0.16f);
            write("评分模型：格式可解析 30 + 结构完整 25 + 关键词 30 + 内容质量 15",
                    9, false, MARGIN + 170, top - 58, 0.42f, 0.5f, 0.48f);
            y = top - boxH - 12;
        }

        void bar(String label, int value, int max) throws Exception {
            ensure(24);
            write(label, 10, false, MARGIN, y, 0.23f, 0.33f, 0.32f);
            float x = MARGIN + 110;
            float barW = CONTENT_W - 160;
            cs.setNonStrokingColor(0.9f, 0.94f, 0.93f);
            cs.addRect(x, y + 1, barW, 6);
            cs.fill();
            cs.setNonStrokingColor(0.21f, 0.78f, 0.73f);
            cs.addRect(x, y + 1, barW * value / max, 6);
            cs.fill();
            write(value + "/" + max, 9, true, x + barW + 10, y, 0.23f, 0.33f, 0.32f);
            y -= 20;
        }

        void issue(AtsIssue issue) throws Exception {
            List<String> head = wrap("[" + issue.level() + "] " + issue.category() + " · " + issue.issue(),
                    10, CONTENT_W - 20);
            List<String> body = wrap("建议：" + issue.action(), 9, CONTENT_W - 20);
            ensure(20 + (head.size() + body.size()) * 14);
            float top = y;
            float height = 10 + (head.size() + body.size()) * 13;
            cs.setNonStrokingColor(0.97f, 0.98f, 0.98f);
            cs.addRect(MARGIN, top - height, CONTENT_W, height);
            cs.fill();
            float color = "P0".equals(issue.level()) ? 0.9f : "P1".equals(issue.level()) ? 0.94f : 0.52f;
            cs.setNonStrokingColor(color, 0.45f, 0.28f);
            cs.addRect(MARGIN, top - height, 3, height);
            cs.fill();
            y = top - 12;
            for (String row : head) {
                write(row, 10, true, MARGIN + 12, y, 0.12f, 0.18f, 0.17f);
                y -= 13;
            }
            for (String row : body) {
                write(row, 9, false, MARGIN + 12, y, 0.42f, 0.5f, 0.48f);
                y -= 13;
            }
            y -= 6;
        }

        void footer(String text) throws Exception {
            ensure(30);
            for (String row : wrap(text, 8, CONTENT_W)) {
                write(row, 8, false, MARGIN, y, 0.55f, 0.6f, 0.58f);
                y -= 12;
            }
        }

        private void write(String text, float size, boolean bold, float x, float y,
                           float r, float g, float b) throws Exception {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(r, g, b);
            cs.newLineAtOffset(x, y);
            cs.showText(text);
            cs.endText();
        }

        private float width(String text, float size) throws Exception {
            return font.getStringWidth(text) / 1000f * size;
        }

        private List<String> wrap(String text, float size, float maxWidth) throws Exception {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isBlank()) return lines;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    lines.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
                if (width(current.toString(), size) > maxWidth) {
                    int cut = current.length() - 1;
                    // 优先在最近的空格处断行
                    int space = current.lastIndexOf(" ");
                    if (space > current.length() * 0.6) cut = space;
                    lines.add(current.substring(0, cut));
                    String rest = current.substring(cut).trim();
                    current.setLength(0);
                    current.append(rest);
                }
            }
            if (current.length() > 0) lines.add(current.toString());
            return lines;
        }

        void close() throws Exception {
            if (cs != null) cs.close();
        }
    }
}
