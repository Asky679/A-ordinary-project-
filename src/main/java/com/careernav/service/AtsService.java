package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATS 模拟检查器。
 *
 * <p>四层评分：格式可解析性 30 / 结构完整性 25 / 关键词匹配 30 / 内容质量 15。
 * 需要说明的是：没有公开的统一 ATS 算法，不同厂商行为不同，本模块依据公开的
 * ATS 兼容性最佳实践给出风险提示，不代表任何具体厂商的真实判定结果。
 */
@Service
public class AtsService {
    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern DATE_DOT = Pattern.compile("20\\d{2}[./]\\d{1,2}");
    private static final Pattern DATE_CN = Pattern.compile("20\\d{2}年\\d{1,2}月");
    private static final Pattern HAS_NUMBER = Pattern.compile("\\d+");

    private static final Map<String, List<String>> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("教育背景", List.of("教育", "学历", "学校", "毕业", "本科", "硕士", "博士"));
        SECTIONS.put("专业技能", List.of("技能", "技术栈", "掌握", "熟悉", "熟练"));
        SECTIONS.put("项目经历", List.of("项目经历", "项目经验", "项目"));
        SECTIONS.put("实习/工作经历", List.of("工作经历", "实习经历", "工作经验", "实习"));
        SECTIONS.put("自我评价", List.of("自我评价", "个人评价", "自我介绍", "个人总结"));
    }

    private static final List<String> ACTION_WORDS = List.of("负责", "主导", "设计", "实现", "优化", "推动", "构建", "落地");
    private static final List<String> TECH_KEYWORDS = List.of(
            "Java", "Python", "Go", "C++", "C#", "JavaScript", "TypeScript", "Spring", "Spring Boot",
            "Django", "Flask", "FastAPI", "MyBatis", "MySQL", "PostgreSQL", "Oracle", "Redis", "MongoDB",
            "Elasticsearch", "Kafka", "RabbitMQ", "Docker", "Kubernetes", "Linux", "Nginx", "Git",
            "Vue", "React", "HTML", "CSS", "SQL", "Shell", "微服务", "分布式", "高并发", "算法");

    private final JobService jobs;

    public AtsService(JobService jobs) {
        this.jobs = jobs;
    }

    public AtsReport check(MultipartFile file, Long jobId) {
        if (file == null || file.isEmpty()) throw new BusinessException("请上传简历文件（PDF 或 DOCX）");
        if (file.getSize() > MAX_SIZE) throw new BusinessException("文件超过 5MB，请压缩后重试");

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (Exception e) { throw new BusinessException("文件读取失败"); }

        Extracted extracted;
        if (name.endsWith(".pdf")) {
            extracted = extractPdf(bytes);
        } else if (name.endsWith(".docx")) {
            extracted = extractDocx(bytes);
        } else if (name.endsWith(".txt") || name.endsWith(".md")) {
            extracted = new Extracted(new String(bytes, StandardCharsets.UTF_8), 1, 0, 0, "TEXT");
        } else {
            throw new BusinessException("暂不支持该格式，请上传 PDF、DOCX 或 TXT");
        }

        String text = extracted.text() == null ? "" : extracted.text();
        List<AtsIssue> issues = new ArrayList<>();

        int parseability = checkFormat(extracted, text, issues);
        int structure = checkStructure(text, issues);
        KeywordResult keywords = checkKeywords(text, jobId, issues);
        int content = checkContent(text, issues);

        int total = parseability + structure + keywords.score() + content;
        String level = total >= 85 ? "格式与内容都较完整，可以投递"
                : total >= 70 ? "可投递，建议先修复 P1 问题"
                : total >= 55 ? "存在明显问题，建议优化后再投递"
                : "风险较高，请先按 P0 问题修改";

        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("fileType", extracted.fileType());
        parsed.put("pages", extracted.pages());
        parsed.put("tables", extracted.tables());
        parsed.put("pictures", extracted.pictures());
        parsed.put("textLength", text.length());
        parsed.put("sectionsFound", foundSections(text));
        parsed.put("hasEmail", EMAIL.matcher(text).find());
        parsed.put("hasPhone", PHONE.matcher(text).find());
        parsed.put("keywordCoverage", keywords.coverage());
        parsed.put("keywordEvaluated", jobId != null);
        parsed.put("matchedKeywords", keywords.matched());
        parsed.put("missingKeywords", keywords.missing());
        if (jobId != null) {
            try { parsed.put("jobTitle", jobs.get(jobId).title()); } catch (Exception ignored) { }
        }

        return new AtsReport(total, level,
                new AtsBreakdown(parseability, structure, keywords.score(), content), issues, parsed);
    }

    // ---------------------------------------------------------------- L1 格式
    private int checkFormat(Extracted ex, String text, List<AtsIssue> issues) {
        int score = 30;
        if (text.trim().length() < 100) {
            score -= 30;
            issues.add(new AtsIssue("P0", "格式",
                    "几乎没有提取到文本，可能是扫描件或图片型简历",
                    "导出为文本型 PDF 或 DOCX，确保文字可以被选中复制"));
            return Math.max(0, score);
        }
        if (ex.tables() > 0) {
            int penalty = Math.min(10, ex.tables() * 4);
            score -= penalty;
            issues.add(new AtsIssue("P1", "格式",
                    "检测到 " + ex.tables() + " 个表格，部分 ATS 会把表格内容读乱",
                    "改用单栏纯文本排版，用段落代替表格线"));
        }
        if (ex.fileType().startsWith("PDF") && looksMultiColumn(text)) {
            score -= 8;
            issues.add(new AtsIssue("P1", "格式",
                    "检测到疑似多栏排版，ATS 可能按错误顺序读取",
                    "改成单栏从上到下的线性排版"));
        }
        if (ex.pictures() > 2) {
            score -= 4;
            issues.add(new AtsIssue("P2", "格式",
                    "文档含 " + ex.pictures() + " 张图片，图片中的文字 ATS 读不到",
                    "不要把关键信息（技能、经历）放在图片里"));
        }
        if (ex.pages() > 3) {
            score -= 3;
            issues.add(new AtsIssue("P2", "格式",
                    "简历共 " + ex.pages() + " 页，超过常见的 1~2 页",
                    "压缩到 1~2 页，删除与岗位无关的内容"));
        }
        return Math.max(0, score);
    }

    private boolean looksMultiColumn(String text) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 8) return false;
        int wide = 0;
        for (String line : lines) {
            if (line.matches(".*\\S\\s{3,}\\S.*")) wide++;
        }
        return wide * 1.0 / lines.length > 0.25;
    }

    // ---------------------------------------------------------------- L2 结构
    private int checkStructure(String text, List<AtsIssue> issues) {
        int score = 25;
        List<String> found = foundSections(text);
        if (found.size() < 5) {
            int penalty = (5 - found.size()) * 4;
            score -= penalty;
            List<String> missing = new ArrayList<>(SECTIONS.keySet());
            missing.removeAll(found);
            issues.add(new AtsIssue("P1", "结构",
                    "缺少标准区块：" + String.join("、", missing),
                    "使用「教育背景 / 专业技能 / 项目经历 / 实习经历 / 自我评价」作为小标题"));
        }
        boolean hasEmail = EMAIL.matcher(text).find();
        boolean hasPhone = PHONE.matcher(text).find();
        if (!hasEmail) {
            score -= 3;
            issues.add(new AtsIssue("P1", "结构", "没有识别到邮箱", "在简历顶部用纯文本写明邮箱，不要放在页眉或图片里"));
        }
        if (!hasPhone) {
            score -= 3;
            issues.add(new AtsIssue("P1", "结构", "没有识别到手机号", "在简历顶部用纯文本写明手机号"));
        }
        boolean dot = DATE_DOT.matcher(text).find();
        boolean cn = DATE_CN.matcher(text).find();
        if (dot && cn) {
            score -= 3;
            issues.add(new AtsIssue("P2", "结构", "日期格式混用（如 2023.09 与 2023年9月）",
                    "统一成一种格式，例如全部使用 2023.09–2025.06"));
        }
        if (text.trim().length() < 500) {
            score -= 3;
            issues.add(new AtsIssue("P2", "结构", "正文内容偏少", "补充项目职责、技术细节和可量化结果"));
        }
        return Math.max(0, score);
    }

    private List<String> foundSections(String text) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : SECTIONS.entrySet()) {
            if (entry.getValue().stream().anyMatch(text::contains)) found.add(entry.getKey());
        }
        return found;
    }

    // ---------------------------------------------------------------- L3 关键词
    private KeywordResult checkKeywords(String text, Long jobId, List<AtsIssue> issues) {
        if (jobId == null) {
            // 没有目标岗位时无法判断"该有哪些关键词"，给中性分而不惩罚
            issues.add(new AtsIssue("P2", "关键词",
                    "未选择目标岗位，关键词匹配未评估",
                    "选择目标岗位后可精确评估关键词覆盖"));
            return new KeywordResult(24, List.of(), List.of(), 0);
        }
        List<String> required;
        JobView job = jobs.get(jobId);
        LinkedHashSet<String> set = new LinkedHashSet<>(job.skills() == null ? List.of() : job.skills());
        for (String keyword : TECH_KEYWORDS) {
            if (job.jobRequirements() != null && job.jobRequirements().toLowerCase().contains(keyword.toLowerCase())) {
                set.add(keyword);
            }
        }
        required = new ArrayList<>(set);
        if (required.isEmpty()) required = new ArrayList<>(TECH_KEYWORDS.subList(0, 10));
        if (required.isEmpty()) return new KeywordResult(24, List.of(), List.of(), 0);

        String lower = text.toLowerCase();
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : required) {
            if (lower.contains(keyword.toLowerCase())) matched.add(keyword);
            else missing.add(keyword);
        }
        double coverage = matched.size() * 1.0 / required.size();
        int score = (int) Math.round(30 * coverage);
        if (coverage < 0.6) {
            issues.add(new AtsIssue("P1", "关键词",
                    "岗位关键词覆盖率 " + Math.round(coverage * 100) + "%，缺失：" + String.join("、", missing.stream().limit(6).toList()),
                    "在项目描述中自然体现这些技术，不要只在技能栏堆砌"));
        }
        return new KeywordResult(score, matched, missing, (int) Math.round(coverage * 100));
    }

    // ---------------------------------------------------------------- L4 内容
    private int checkContent(String text, List<AtsIssue> issues) {
        int score = 0;
        long actions = ACTION_WORDS.stream().filter(text::contains).count();
        if (actions >= 2) score += 5;
        else issues.add(new AtsIssue("P2", "内容", "缺少动作动词（负责/主导/设计/实现/优化）",
                "每条经历用动词开头，明确你做了什么"));

        Matcher matcher = HAS_NUMBER.matcher(text);
        int numbers = 0;
        while (matcher.find() && numbers < 20) numbers++;
        if (numbers >= 3) score += 5;
        else issues.add(new AtsIssue("P2", "内容", "可量化的数据偏少",
                "补充基线→结果，例如 QPS 从 300 提升到 1200、P95 从 480ms 降到 90ms"));

        if (text.trim().length() >= 800) score += 5;
        else if (text.trim().length() >= 400) score += 3;
        else issues.add(new AtsIssue("P2", "内容", "内容偏短，信息量不足", "补充技术选型、难点与个人贡献"));
        return score;
    }

    // ---------------------------------------------------------------- 解析
    private Extracted extractPdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return new Extracted(text, doc.getNumberOfPages(), 0, 0, "PDF");
        } catch (Exception e) {
            throw new BusinessException("PDF 解析失败，文件可能已损坏或加密");
        }
    }

    private Extracted extractDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = new XWPFWordExtractor(doc).getText();
            int tables = doc.getTables() == null ? 0 : doc.getTables().size();
            int pictures = doc.getAllPictures() == null ? 0 : doc.getAllPictures().size();
            int pages = Math.max(1, (int) Math.ceil(text.length() / 1800.0));
            return new Extracted(text, pages, tables, pictures, "DOCX");
        } catch (Exception e) {
            throw new BusinessException("DOCX 解析失败，文件可能已损坏");
        }
    }

    private record Extracted(String text, int pages, int tables, int pictures, String fileType) {}

    private record KeywordResult(int score, List<String> matched, List<String> missing, int coverage) {}
}
