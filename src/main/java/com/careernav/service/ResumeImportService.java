package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.ExperienceItem;
import com.careernav.domain.ApiModels.ProjectItem;
import com.careernav.domain.ApiModels.ResumeImportResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 PDF 文本、图片 OCR 文本解析成可编辑的简历草稿。
 *
 * <p>此服务只返回草稿，不直接保存。OCR 和模型都可能误识别数字、学校名和专有名词，
 * 因此前端必须让用户确认后再写入简历仓库。</p>
 */
@Service
public class ResumeImportService {
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static final int MAX_TEXT = 30_000;
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SCHOOL = Pattern.compile("[\\u4e00-\\u9fa5]{2,24}(?:大学|学院|学校)");
    private static final Pattern MAJOR = Pattern.compile("(?:专业|主修)[:：\\s]*([^\\r\\n，,；;]{2,40})");
    private static final Pattern PERIOD = Pattern.compile(
            "(20\\d{2}(?:[./年-]\\d{1,2})?\\s*[-—至到]\\s*(?:20\\d{2}(?:[./年-]\\d{1,2})?|至今))");
    private static final Pattern ROLE_VALUE = Pattern.compile(
            "(?:求职意向|目标岗位|应聘岗位|求职目标|期望职位|意向岗位)[:：\\s]*([^\\r\\n]{2,50})");

    private static final List<String> SECTION_PROJECT = List.of(
            "项目经历", "项目经验", "主要项目经历", "主要项目", "实践项目", "项目实践", "核心项目", "项目作品");
    private static final List<String> SECTION_EXPERIENCE = List.of(
            "实习经历", "工作经历", "工作经验", "实践经历", "工作履历", "任职经历", "职业经历", "实习/工作经历");
    private static final List<String> SECTION_SKILL = List.of(
            "专业技能", "技能清单", "技术栈", "技能特长", "掌握技能", "专业能力", "技术能力");
    private static final List<String> SECTION_SELF = List.of(
            "自我评价", "个人评价", "自我介绍", "个人总结", "个人优势");
    private static final List<String> STOP_SECTIONS = List.of(
            "教育背景", "教育经历", "专业技能", "技能清单", "技术栈", "技能特长", "项目经历", "项目经验",
            "主要项目", "项目实践", "核心项目", "项目作品", "实习经历", "工作经历", "工作经验", "工作履历",
            "任职经历", "职业经历", "校园经历", "获奖经历", "获奖情况", "荣誉奖项", "荣誉奖励", "证书",
            "资格证书", "自我评价", "个人评价", "个人总结", "个人信息", "求职意向");
    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "长沙", "重庆", "天津",
            "苏州", "郑州", "合肥", "厦门", "福州", "济南", "青岛", "宁波", "无锡", "东莞", "佛山", "珠海",
            "南昌", "昆明", "贵阳", "南宁", "海口", "太原", "石家庄", "沈阳", "大连", "长春", "哈尔滨", "兰州");
    private static final List<String> ROLE_HINTS = List.of(
            "Java后端", "Java开发", "Python开发", "前端开发", "后端开发", "全栈开发", "软件开发", "软件测试",
            "测试开发", "运维开发", "数据分析", "数据开发", "大数据开发", "算法工程师", "机器学习", "人工智能",
            "网络安全", "嵌入式", "Android开发", "iOS开发", "游戏开发", "计算机视觉", "自然语言处理");
    private static final List<String> TECH_SKILLS = List.of(
            "Spring Boot", "Spring Cloud", "Spring", "MyBatis", "Java", "Python", "C++", "C#", "C", "Go",
            "JavaScript", "TypeScript", "HTML5", "CSS3", "HTML", "CSS", "Vue", "React", "Node.js", "Django",
            "Flask", "FastAPI", "MySQL", "PostgreSQL", "Oracle", "SQL Server", "SQL", "Redis", "MongoDB",
            "Elasticsearch", "Kafka", "RabbitMQ", "Docker", "Kubernetes", "K8s", "Linux", "Nginx", "Git",
            "Maven", "Gradle", "JUnit", "Pytest", "Selenium", "Postman", "PyTorch", "TensorFlow", "Pandas",
            "NumPy", "LangChain", "RAG", "MCP", "微服务", "分布式", "高并发", "算法", "数据结构");

    private final AgentModelClient model;

    public ResumeImportService(AgentModelClient model) {
        this.model = model;
    }

    public ResumeImportResult importResume(MultipartFile file, String clientText, String clientMethod) {
        if (file == null || file.isEmpty()) throw new BusinessException("请选择 PDF、DOCX 或简历图片");
        if (file.getSize() > MAX_SIZE) throw new BusinessException("导入文件不能超过 10MB");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception exception) {
            throw new BusinessException("文件读取失败");
        }

        String fallbackMethod = methodFor(filename, clientMethod);
        String text = chooseText(filename, bytes, clientText);
        if (text.length() < 20) {
            throw new BusinessException("没有识别到足够的简历文字。扫描件请确认 OCR 已完成，或换一张清晰图片");
        }
        text = clip(text, MAX_TEXT);
        Heuristic heuristic = heuristic(text);
        ModelDraft ai = parseWithModel(maskContacts(text));
        Merged merged = merge(ai, heuristic);

        List<String> warnings = new ArrayList<>();
        if (merged.ownerName().equals("待确认姓名")) warnings.add("未可靠识别姓名，请在保存前手动填写");
        if (merged.targetRole().equals("待确认")) warnings.add("未可靠识别目标岗位，请选择或填写求职方向");
        if (merged.phone().isBlank() && merged.email().isBlank()) warnings.add("未识别到手机号或邮箱");
        if (merged.education().isBlank()) warnings.add("未识别到学历信息");
        if (merged.skills().isEmpty()) warnings.add("未识别到技能关键词");
        if (merged.projects().isEmpty() && merged.experiences().isEmpty()) warnings.add("未识别到项目或实习经历");
        if (hasHeading(text, SECTION_PROJECT) && merged.projects().isEmpty()) {
            warnings.add("检测到项目经历标题，但没有拆出项目条目，请展开原文核对后手动补充");
        }
        if (hasHeading(text, SECTION_EXPERIENCE) && merged.experiences().isEmpty()) {
            warnings.add("检测到实习/工作经历标题，但没有拆出经历条目，请展开原文核对后手动补充");
        }
        if (fallbackMethod.contains("OCR") || fallbackMethod.contains("MIXED")) {
            warnings.add("OCR 可能误识别数字、公司名和专有名词，请逐项核对");
        }
        warnings.add("导入结果不会自动保存；确认内容后再点击“保存到仓库”");

        int confidence = confidence(merged, ai != null, fallbackMethod);
        return new ResumeImportResult(
                merged.ownerName(), merged.targetRole(), merged.city(), merged.phone(), merged.email(),
                merged.education(), merged.school(), merged.major(), merged.graduationYear(),
                merged.skills(), merged.projects(), merged.experiences(), merged.selfEvaluation(), text,
                fallbackMethod, confidence, List.copyOf(warnings),
                confidence < 80 || fallbackMethod.contains("OCR"));
    }

    private String chooseText(String filename, byte[] bytes, String clientText) {
        String client = cleanText(clientText);
        if (filename.endsWith(".pdf")) {
            String server = extractPdf(bytes);
            if (client.length() < 20) return server;
            if (server.length() < 40) return client;
            // 浏览器 PDF.js 会按页面坐标重建换行，通常比服务端连续文本更适合识别栏目。
            if (structureScore(client) >= structureScore(server)) return client;
            return server.length() >= client.length() ? server : client;
        }
        if (filename.endsWith(".docx")) {
            String server = extractDocx(bytes);
            return server.length() >= 20 ? server : client;
        }
        if (filename.endsWith(".txt") || filename.endsWith(".md")) {
            return cleanText(new String(bytes, StandardCharsets.UTF_8));
        }
        if (!client.isBlank()) return client;
        throw new BusinessException("图片简历需要先在浏览器中完成 OCR");
    }

    private int structureScore(String text) {
        if (text == null || text.isBlank()) return -1000;
        String[] lines = text.split("\\R");
        int score = Math.min(60, lines.length);
        for (List<String> headings : List.of(SECTION_PROJECT, SECTION_EXPERIENCE, SECTION_SKILL, SECTION_SELF)) {
            if (hasHeading(text, headings)) score += 16;
        }
        Matcher periods = PERIOD.matcher(text);
        int periodCount = 0;
        while (periods.find() && periodCount < 20) periodCount++;
        score += periodCount * 4;
        if (lines.length <= 2 && text.length() > 500) score -= 20;
        return score;
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return cleanText(stripper.getText(document));
        } catch (Exception exception) {
            throw new BusinessException("PDF 读取失败，文件可能已损坏或加密");
        }
    }

    private String extractDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return cleanText(new XWPFWordExtractor(document).getText());
        } catch (Exception exception) {
            throw new BusinessException("DOCX 读取失败，文件可能已损坏");
        }
    }

    private String maskContacts(String text) {
        String masked = PHONE.matcher(text).replaceAll("[手机号已隐藏]");
        return EMAIL.matcher(masked).replaceAll("[邮箱已隐藏]");
    }

    private ModelDraft parseWithModel(String text) {
        if (!model.available()) return null;
        return model.generateJson("""
                你是简历结构化解析器。请从下面的 OCR/PDF 文本中抽取一份计算机学生简历。
                不得编造原文没有的姓名、联系方式、学校、时间、项目、技能或成果。无法确定就返回空字符串或空数组。
                项目描述保留原文事实，不要润色，不要添加数字。
                必须逐条输出所有项目经历和所有实习/工作经历，禁止把多段经历合并成一条，也不要重复同一条。
                项目名称、担任角色、技术栈、描述、难点、成果要分别填写；公司、职位、时间必须保持原文。
                返回 JSON：
                {
                  "ownerName":"姓名","targetRole":"求职方向","city":"期望城市","phone":"手机号","email":"邮箱",
                  "education":"本科/硕士/博士/大专/其他","school":"学校","major":"专业","graduationYear":2026,
                  "skills":["Java","Spring Boot"],
                  "projects":[{"name":"项目名","role":"个人角色","techStack":"技术栈","description":"背景、职责与实现","challenge":"难点、取舍与故障处理","highlight":"验证结果与个人贡献"}],
                  "experiences":[{"company":"公司","position":"职位","period":"时间","description":"工作内容","achievement":"成果"}],
                  "selfEvaluation":"自我评价"
                }
                原文：
                <<<%s>>>
                """.formatted(clip(text, 14_000)), ModelDraft.class);
    }

    private Heuristic heuristic(String text) {
        Matcher emailMatcher = EMAIL.matcher(text);
        String email = emailMatcher.find() ? emailMatcher.group() : "";
        Matcher phoneMatcher = PHONE.matcher(text);
        String phone = phoneMatcher.find() ? phoneMatcher.group() : "";
        String school = first(SCHOOL, text);
        String major = group(MAJOR, text);
        String education = detectEducation(text);
        Integer graduationYear = detectGraduationYear(text);
        String targetRole = detectRole(text);
        String ownerName = detectName(text);
        String city = detectCity(text);
        String projectText = section(text, SECTION_PROJECT);
        String experienceText = section(text, SECTION_EXPERIENCE);
        String selfEvaluation = section(text, SECTION_SELF);
        List<String> skills = detectSkills(text);
        List<ProjectItem> projects = projectsFrom(projectText);
        List<ExperienceItem> experiences = experiencesFrom(experienceText);
        return new Heuristic(ownerName, targetRole, city, phone, email, education, school, major,
                graduationYear, skills, projects, experiences, clip(selfEvaluation, 500));
    }

    private Merged merge(ModelDraft ai, Heuristic fallback) {
        String ownerName = value(ai == null ? null : ai.ownerName(), fallback.ownerName(), "待确认姓名");
        String targetRole = value(ai == null ? null : ai.targetRole(), fallback.targetRole(), "待确认");
        String city = value(ai == null ? null : ai.city(), fallback.city(), "");
        String phone = value(ai == null ? null : ai.phone(), fallback.phone(), "");
        String email = value(ai == null ? null : ai.email(), fallback.email(), "");
        String education = normalizeEducation(value(ai == null ? null : ai.education(), fallback.education(), ""));
        String school = value(ai == null ? null : ai.school(), fallback.school(), "");
        String major = value(ai == null ? null : ai.major(), fallback.major(), "");
        Integer year = ai != null && ai.graduationYear() != null ? ai.graduationYear() : fallback.graduationYear();

        // 模型和规则解析互为补充：模型只返回一条时，不能覆盖规则解析出的其他项目与经历。
        List<String> skills = normalizeSkills(mergeStrings(
                ai == null ? List.of() : ai.skills(), fallback.skills()));
        List<ProjectItem> projects = normalizeProjects(mergeProjects(
                ai == null ? List.of() : ai.projects(), fallback.projects()));
        List<ExperienceItem> experiences = normalizeExperiences(mergeExperiences(
                ai == null ? List.of() : ai.experiences(), fallback.experiences()));
        String self = value(ai == null ? null : ai.selfEvaluation(), fallback.selfEvaluation(), "");
        return new Merged(ownerName, targetRole, city, phone, email, education, school, major, year,
                skills, projects, experiences, self);
    }

    private List<String> mergeStrings(List<String> preferred, List<String> fallback) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : preferred == null ? List.<String>of() : preferred) values.add(clean(item));
        for (String item : fallback == null ? List.<String>of() : fallback) values.add(clean(item));
        values.remove("");
        return List.copyOf(values);
    }

    private List<ProjectItem> mergeProjects(List<ProjectItem> preferred, List<ProjectItem> fallback) {
        List<ProjectItem> merged = new ArrayList<>();
        appendProjects(merged, preferred);
        appendProjects(merged, fallback);
        return merged;
    }

    private void appendProjects(List<ProjectItem> target, List<ProjectItem> rows) {
        for (ProjectItem row : rows == null ? List.<ProjectItem>of() : rows) {
            if (row == null) continue;
            int index = findProject(target, row);
            if (index < 0) {
                target.add(row);
            } else {
                target.set(index, mergeProject(target.get(index), row));
            }
        }
    }

    private int findProject(List<ProjectItem> rows, ProjectItem candidate) {
        String key = identity(candidate.name());
        if (key.isBlank()) return -1;
        for (int i = 0; i < rows.size(); i++) {
            String existing = identity(rows.get(i).name());
            if (!existing.isBlank() && (existing.equals(key) || existing.contains(key) || key.contains(existing))) return i;
        }
        return -1;
    }

    private ProjectItem mergeProject(ProjectItem first, ProjectItem second) {
        return new ProjectItem(
                firstNonBlank(first.name(), second.name()),
                firstNonBlank(first.role(), second.role()),
                firstNonBlank(first.techStack(), second.techStack()),
                firstNonBlank(first.description(), second.description()),
                firstNonBlank(first.challenge(), second.challenge()),
                firstNonBlank(first.highlight(), second.highlight()));
    }

    private List<ExperienceItem> mergeExperiences(List<ExperienceItem> preferred, List<ExperienceItem> fallback) {
        List<ExperienceItem> merged = new ArrayList<>();
        appendExperiences(merged, preferred);
        appendExperiences(merged, fallback);
        return merged;
    }

    private void appendExperiences(List<ExperienceItem> target, List<ExperienceItem> rows) {
        for (ExperienceItem row : rows == null ? List.<ExperienceItem>of() : rows) {
            if (row == null) continue;
            int index = findExperience(target, row);
            if (index < 0) {
                target.add(row);
            } else {
                target.set(index, mergeExperience(target.get(index), row));
            }
        }
    }

    private int findExperience(List<ExperienceItem> rows, ExperienceItem candidate) {
        String company = identity(candidate.company());
        String position = identity(candidate.position());
        String period = identity(candidate.period());
        for (int i = 0; i < rows.size(); i++) {
            ExperienceItem existing = rows.get(i);
            boolean companyMatch = !company.isBlank() && !identity(existing.company()).isBlank()
                    && (identity(existing.company()).contains(company) || company.contains(identity(existing.company())));
            boolean positionMatch = !position.isBlank() && !identity(existing.position()).isBlank()
                    && (identity(existing.position()).contains(position) || position.contains(identity(existing.position())));
            boolean periodMatch = !period.isBlank() && period.equals(identity(existing.period()));
            if ((companyMatch && (position.isBlank() || positionMatch)) || (periodMatch && (companyMatch || positionMatch))) return i;
        }
        return -1;
    }

    private ExperienceItem mergeExperience(ExperienceItem first, ExperienceItem second) {
        return new ExperienceItem(
                firstNonBlank(first.company(), second.company()),
                firstNonBlank(first.position(), second.position()),
                firstNonBlank(first.period(), second.period()),
                firstNonBlank(first.description(), second.description()),
                firstNonBlank(first.achievement(), second.achievement()));
    }

    private String firstNonBlank(String first, String second) {
        return clean(first).isBlank() ? clean(second) : clean(first);
    }

    private String identity(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：、（）【】《》·—－]", "");
    }

    private List<ProjectItem> normalizeProjects(List<ProjectItem> rows) {
        return rows.stream().filter(item -> item != null)
                .map(item -> new ProjectItem(
                        value(item.name(), "", "待确认项目"), clean(item.role()), clean(item.techStack()),
                        clip(clean(item.description()), 1600), clip(clean(item.challenge()), 800),
                        clip(clean(item.highlight()), 800)))
                .limit(10).toList();
    }

    private List<ExperienceItem> normalizeExperiences(List<ExperienceItem> rows) {
        return rows.stream().filter(item -> item != null && !(value(item.company(), "", "").isBlank()
                        && value(item.position(), "", "").isBlank() && value(item.description(), "", "").isBlank()))
                .map(item -> new ExperienceItem(clean(item.company()), clean(item.position()), clean(item.period()),
                        clip(clean(item.description()), 1400), clip(clean(item.achievement()), 800)))
                .limit(10).toList();
    }

    private List<String> normalizeSkills(List<String> rows) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String row : rows == null ? List.<String>of() : rows) {
            String value = clean(row);
            if (!value.isBlank() && value.length() <= 40) values.add(value);
            if (values.size() >= 30) break;
        }
        return List.copyOf(values);
    }

    private int confidence(Merged value, boolean modelUsed, String method) {
        int score = 18;
        if (!value.ownerName().equals("待确认姓名")) score += 15;
        if (!value.targetRole().equals("待确认")) score += 15;
        if (!value.phone().isBlank() || !value.email().isBlank()) score += 10;
        if (!value.education().isBlank()) score += 10;
        if (!value.school().isBlank()) score += 7;
        if (!value.skills().isEmpty()) score += 10;
        if (!value.projects().isEmpty() || !value.experiences().isEmpty()) score += 15;
        if (modelUsed) score += 5;
        if (method.contains("OCR")) score -= 5;
        return Math.max(0, Math.min(95, score));
    }

    private List<String> detectSkills(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String skill : TECH_SKILLS) {
            if (lower.contains(skill.toLowerCase(Locale.ROOT))) result.add(skill);
        }
        String skillSection = section(text, SECTION_SKILL);
        if (!skillSection.isBlank()) {
            for (String token : skillSection.split("[,，、/|；;\\n]")) {
                String value = token.replaceAll("^(熟悉|掌握|了解|熟练使用|精通)[:：\\s]*", "").trim();
                if (value.matches("[A-Za-z][A-Za-z0-9+#. _-]{1,28}") && !value.matches("(?i).*(熟悉|掌握|了解|负责).*")) {
                    result.add(value);
                }
            }
        }
        return result.stream().limit(30).toList();
    }

    private List<ProjectItem> projectsFrom(String section) {
        List<String> lines = nonBlankLines(section).stream().map(this::stripBullet).toList();
        if (lines.isEmpty()) return List.of();
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (!current.isEmpty() && startsNewProject(line, current)) {
                blocks.add(List.copyOf(current));
                current.clear();
            }
            current.add(line);
        }
        if (!current.isEmpty()) blocks.add(List.copyOf(current));
        return blocks.stream().map(this::projectFromBlock)
                .filter(item -> !item.name().isBlank()).limit(10).toList();
    }

    private boolean startsNewProject(String line, List<String> current) {
        if (isProjectMetaLine(line) || isProjectBodyLine(line)) return false;
        boolean hasBody = current.stream().anyMatch(this::isProjectBodyLine);
        if (!hasBody) return false;
        return PERIOD.matcher(line).find() || looksLikeProjectTitle(line);
    }

    private boolean looksLikeProjectTitle(String line) {
        String value = clean(line);
        if (value.length() < 2 || value.length() > 90) return false;
        if (value.matches("^20\\d{2}.*$") && value.length() < 18) return false;
        return value.matches(".*(?:项目|平台|系统|网站|应用|小程序|App|中台|引擎|工具|服务|商城|社区|助手|作品|客户端|管理端).*")
                || value.matches(".*(?:负责人|开发|工程师|成员|组长).*");
    }

    private boolean isProjectBodyLine(String line) {
        String value = stripBullet(line);
        return value.matches("^(?:负责|参与|实现|开发|设计|使用|完成|搭建|优化|编写|对接|维护|测试|部署|主导|独立|配合|根据|通过|基于|针对|将|在|对|商品|订单|用户|系统|接口|前端|后端|数据库).*")
                || value.length() > 90;
    }

    private boolean isProjectMetaLine(String line) {
        String value = stripBullet(line);
        return value.matches("^(?:技术栈|技术选型|使用技术|开发环境|项目描述|项目职责|主要工作|工作内容|项目成果|成果|亮点|难点|挑战|解决方案)[:：].*")
                || (PERIOD.matcher(value).find() && value.length() <= 36);
    }

    private ProjectItem projectFromBlock(List<String> block) {
        String title = "";
        String role = "";
        String tech = "";
        List<String> description = new ArrayList<>();
        List<String> challenge = new ArrayList<>();
        List<String> highlight = new ArrayList<>();
        List<String> meta = new ArrayList<>();

        for (String raw : block) {
            String line = stripBullet(raw);
            if (line.isBlank()) continue;
            if (title.isBlank() && !isProjectMetaLine(line) && !isProjectBodyLine(line)) {
                role = extractProjectRole(line);
                title = stripProjectRole(line, role);
                continue;
            }
            if (line.matches("(?i)^(?:技术栈|技术选型|使用技术|开发环境|tech\\s*stack)[:：].*")) {
                tech = line.replaceFirst("(?i)^(?:技术栈|技术选型|使用技术|开发环境|tech\\s*stack)[:：]\\s*", "").trim();
            } else if (line.matches("^(?:难点|挑战|问题|故障|取舍|解决方案)[:：].*")) {
                challenge.add(stripLabel(line));
            } else if (looksLikeResult(line)) {
                highlight.add(stripLabel(line));
            } else if (!isProjectMetaLine(line)) {
                description.add(stripLabel(line));
            } else {
                meta.add(stripLabel(line));
            }
        }

        if (title.isBlank()) {
            title = block.stream().map(this::stripBullet)
                    .filter(line -> !isProjectMetaLine(line) && !isProjectBodyLine(line))
                    .findFirst().orElse("待确认项目");
            role = extractProjectRole(title);
            title = stripProjectRole(title, role);
        }
        if (tech.isBlank()) {
            tech = detectSkills(String.join("\n", block)).stream().limit(10)
                    .reduce((a, b) -> a + " / " + b).orElse("");
        }
        if (!meta.isEmpty()) description.addAll(meta);
        return new ProjectItem(
                clip(title.isBlank() ? "待确认项目" : title, 120), clip(role, 80), clip(tech, 300),
                clip(String.join("\n", description), 1600), clip(String.join("\n", challenge), 800),
                clip(String.join("\n", highlight), 800));
    }

    private String extractProjectRole(String line) {
        String value = clean(line);
        String[] parts = value.split("\\s{1,}|[|｜·•—–]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (part.length() <= 20 && part.matches(".*(?:负责人|开发工程师|开发|工程师|核心成员|成员|组长|实习生|架构师).*")) {
                return part;
            }
        }
        return "";
    }

    private String stripProjectRole(String title, String role) {
        String value = stripPeriod(clean(title)).replaceAll("[|｜·•—–/\\-]\\s*$", "").trim();
        if (role == null || role.isBlank()) return value;
        return value.replaceFirst("\\s*[|｜·•—–/\\-]?\\s*" + Pattern.quote(role) + "\\s*$", "").trim();
    }
    private List<ExperienceItem> experiencesFrom(String section) {
        List<String> lines = nonBlankLines(section).stream().map(this::stripBullet).toList();
        if (lines.isEmpty()) return List.of();
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (!current.isEmpty() && startsNewExperience(line, current)) {
                blocks.add(List.copyOf(current));
                current.clear();
            }
            current.add(line);
        }
        if (!current.isEmpty()) blocks.add(List.copyOf(current));
        return blocks.stream().map(this::experienceFromBlock)
                .filter(item -> !(item.company().isBlank() && item.position().isBlank() && item.description().isBlank()))
                .limit(10).toList();
    }

    private boolean startsNewExperience(String line, List<String> current) {
        boolean currentHasPeriod = current.stream().anyMatch(item -> PERIOD.matcher(item).find());
        boolean currentHasBody = current.stream().anyMatch(this::isExperienceBody);
        if (PERIOD.matcher(line).find() && currentHasPeriod && currentHasBody) return true;
        return currentHasPeriod && currentHasBody && isExperienceHeader(line) && !isExperienceBody(line);
    }

    private boolean isExperienceHeader(String line) {
        String value = stripBullet(line);
        if (isExperienceBody(value)) return false;
        return value.length() <= 100 && (value.matches(".*(?:公司|集团|科技|银行|研究院|工作室|中心|事业部|实验室|事务所).*")
                || value.matches(".*(?:实习生|工程师|开发|测试|运营|助理|负责人|架构师|分析师|经理|专员|设计师).*"));
    }

    private boolean isExperienceBody(String line) {
        String value = stripBullet(line);
        return value.matches("^(?:参与|负责|完成|实现|开发|设计|维护|优化|搭建|对接|编写|测试|部署|独立|配合|根据|通过|基于|主导|协助|支持).*")
                || value.length() > 100;
    }

    private ExperienceItem experienceFromBlock(List<String> block) {
        String joined = String.join("\n", block);
        String period = first(PERIOD, joined);
        String company = "";
        String position = "";
        List<String> content = new ArrayList<>();
        boolean headerMode = true;

        for (String raw : block) {
            String line = stripBullet(raw);
            if (line.isBlank()) continue;
            if (headerMode && isPeriodOnlyLine(line)) continue;
            if (headerMode && isExperienceHeader(line)) {
                String[] parsed = parseExperienceHeader(line);
                if (company.isBlank()) company = parsed[0];
                if (position.isBlank()) position = parsed[1];
                continue;
            }
            headerMode = false;
            content.add(stripLabel(line));
        }

        List<String> description = new ArrayList<>();
        List<String> achievement = new ArrayList<>();
        for (String line : content) {
            if (line.isBlank()) continue;
            if (looksLikeResult(line)) achievement.add(line);
            else description.add(line);
        }
        if (company.isBlank() && !position.isBlank()) {
            String positionForCompany = position;
            company = block.stream().map(this::stripBullet)
                    .filter(line -> line.length() <= 80 && !isExperienceBody(line) && !PERIOD.matcher(line).find()
                            && !line.contains(positionForCompany)).findFirst().orElse("");
        }
        if (position.isBlank()) {
            position = block.stream().map(this::stripBullet)
                    .filter(line -> line.matches(".*(?:实习生|工程师|开发|测试|运营|助理|负责人|架构师|分析师|经理|专员|设计师).*"))
                    .findFirst().map(this::stripPeriod).orElse("");
        }
        return new ExperienceItem(clip(company, 160), clip(position, 120), clip(period, 80),
                clip(String.join("\n", description), 1400), clip(String.join("\n", achievement), 800));
    }

    private String[] parseExperienceHeader(String line) {
        String value = stripPeriod(stripBullet(line));
        String[] rawParts = value.split("\\s*[|｜·•—–]\\s*|\\s{2,}");
        String company = "";
        String position = "";
        for (String part : rawParts) {
            String segment = clean(part);
            if (segment.isBlank()) continue;
            if (company.isBlank() && segment.matches(".*(?:有限公司|有限责任公司|公司|集团|银行|研究院|工作室|事业部|实验室|事务所).*")) {
                company = segment;
                continue;
            }
            if (position.isBlank() && segment.matches(".*(?:实习生|工程师|开发|测试|运营|助理|负责人|架构师|分析师|经理|专员|设计师).*")) {
                position = segment;
                continue;
            }
            if (company.isBlank()) company = segment;
        }
        if (company.isBlank() && position.isBlank() && !value.isBlank()) {
            if (value.matches(".*(?:有限公司|有限责任公司|公司|集团|银行|研究院|工作室|事业部|实验室|事务所).*")) company = value;
            else if (value.matches(".*(?:实习生|工程师|开发|测试|运营|助理|负责人|架构师|分析师|经理|专员|设计师).*")) position = value;
        }
        if (!company.isBlank() && position.isBlank()) {
            Matcher splitCompany = Pattern.compile("^(.{1,80}?(?:有限责任公司|有限公司|公司|集团|银行|研究院|工作室|事业部|实验室|事务所))\\s+(.+?)\\s*[|｜·•—–]?$").matcher(company);
            if (splitCompany.find()) {
                company = splitCompany.group(1).trim();
                position = splitCompany.group(2).trim();
            }
        }
        if (!company.isBlank() && company.equals(position)) position = "";
        return new String[]{clean(company), clean(position)};
    }

    private boolean isPeriodOnlyLine(String line) {
        String value = stripBullet(line);
        if (!PERIOD.matcher(value).find()) return false;
        return stripPeriod(value).replaceAll("[\\s|｜·•—–/\\-,]", "").isBlank();
    }
    private String stripPeriod(String value) {
        return clean(value).replaceAll("\\s*(?:19|20)\\d{2}(?:[./年-]\\d{1,2})?\\s*[-—至到]\\s*(?:(?:19|20)\\d{2}(?:[./年-]\\d{1,2})?|至今)\\s*", " ").trim();
    }

    private String stripLabel(String line) {
        return clean(stripBullet(line)).replaceFirst("^(?:工作内容|主要职责|主要负责|项目职责|项目描述|难点|挑战|问题|故障|取舍|解决方案|成果|项目成果|亮点)[:：]\\s*", "").trim();
    }

    private String stripBullet(String line) {
        return clean(line).replaceFirst("^[\\-*+•●▪◦★·]+\\s*", "")
                .replaceFirst("^(?:\\d+|[一二三四五六七八九十]+)[.、)）]\\s*", "").trim();
    }

    private boolean looksLikeResult(String line) {
        return line.matches(".*(?:\\d+%|\\d+ms|\\d+万|\\d+\\+|QPS|TPS|用户|提升|降低|减少|增长|支撑|上线|通过率|准确率|响应时间).*");
    }

    private String detectName(String text) {
        int count = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.trim().replaceAll("\\s+", " ");
            if (line.isBlank()) continue;
            count++;
            if (count > 10) break;
            String compact = line.replace(" ", "");
            if (compact.matches("[\\u4e00-\\u9fa5]{2,4}") && !compact.matches(".*(简历|姓名|信息|求职|个人|联系).*")) {
                return compact;
            }
            if (line.matches("[A-Z][A-Za-z]+(?: [A-Z][A-Za-z]+){1,3}")) return line;
            Matcher matcher = Pattern.compile("(?:姓名|Name)[:：\\s]*([\\u4e00-\\u9fa5]{2,4}|[A-Za-z ]{2,30})",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return "";
    }

    private String detectRole(String text) {
        String explicit = group(ROLE_VALUE, text);
        if (!explicit.isBlank()) return clip(explicit, 50);
        String lower = text.toLowerCase(Locale.ROOT);
        for (String role : ROLE_HINTS) {
            if (lower.contains(role.toLowerCase(Locale.ROOT))) return role;
        }
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (value.matches(".*(?:开发工程师|算法工程师|测试工程师|运维工程师|数据分析师|前端|后端|全栈).*")
                    && value.length() <= 50) return value;
        }
        return "";
    }

    private String detectCity(String text) {
        Matcher matcher = Pattern.compile("(?:现居地|现居|所在地|期望城市|意向城市|城市)[:：\\s]*([\\u4e00-\\u9fa5]{2,8})").matcher(text);
        if (matcher.find()) return matcher.group(1);
        for (String city : CITIES) if (text.contains(city)) return city;
        return "";
    }

    private String detectEducation(String text) {
        if (text.contains("博士")) return "博士";
        if (text.contains("硕士") || text.contains("研究生")) return "硕士";
        if (text.contains("本科") || text.contains("学士")) return "本科";
        if (text.contains("大专") || text.contains("专科")) return "大专";
        return "";
    }

    private Integer detectGraduationYear(String text) {
        Matcher near = Pattern.compile("(?:毕业|预计毕业|毕业时间)[^\\d]{0,12}(20\\d{2})").matcher(text);
        if (near.find()) return Integer.valueOf(near.group(1));
        Matcher years = Pattern.compile("(20(?:2[0-9]|3[0-5]))").matcher(text);
        Integer latest = null;
        while (years.find()) latest = Integer.valueOf(years.group(1));
        return latest;
    }

    private String section(String text, List<String> headings) {
        String[] lines = text.split("\\R");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (matchesHeading(lines[i], headings)) {
                start = i;
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder result = new StringBuilder();
        for (int i = start + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches("^[一二三四五六七八九十0-9 .、]+$") || line.isBlank()) continue;
            if (isStopSection(line)) break;
            result.append(stripBullet(line)).append('\n');
        }
        return result.toString().trim();
    }

    private boolean hasHeading(String text, List<String> headings) {
        return text != null && text.lines().anyMatch(line -> matchesHeading(line, headings));
    }

    private boolean matchesHeading(String line, List<String> headings) {
        String normalized = normalizedHeading(line);
        return !normalized.isBlank() && headings.stream()
                .map(this::normalizedHeading)
                .anyMatch(keyword -> normalized.contains(keyword));
    }

    private boolean isStopSection(String line) {
        String value = clean(line);
        String normalized = normalizedHeading(value);
        if (normalized.isBlank()) return false;
        return STOP_SECTIONS.stream().anyMatch(keyword -> {
            String target = normalizedHeading(keyword);
            return value.length() <= 40 && !value.contains(":") && !value.contains("：")
                    && normalized.contains(target);
        });
    }

    private String normalizedHeading(String value) {
        return clean(value).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、（）【】《》·—－]", "");
    }

    private List<String> nonBlankLines(String text) {
        return text.lines().map(String::trim).filter(line -> !line.isBlank()).limit(30).toList();
    }

    private String methodFor(String filename, String clientMethod) {
        String method = clean(clientMethod).toUpperCase(Locale.ROOT);
        if (method.contains("OCR") || method.contains("MIXED")) return method;
        if (filename.endsWith(".pdf")) return "PDF_TEXT";
        if (filename.endsWith(".docx")) return "DOCX_TEXT";
        if (filename.matches(".*\\.(png|jpe?g|webp)$")) return "OCR_IMAGE";
        return "TEXT";
    }

    private String normalizeEducation(String value) {
        String education = clean(value);
        if (education.contains("博士")) return "博士";
        if (education.contains("硕士") || education.contains("研究生")) return "硕士";
        if (education.contains("本科") || education.contains("学士")) return "本科";
        if (education.contains("大专") || education.contains("专科")) return "大专";
        return education.isBlank() ? "" : "其他";
    }

    private String value(String preferred, String fallback, String defaultValue) {
        String value = preferred == null ? "" : preferred.trim();
        if (value.isBlank()) value = fallback == null ? "" : fallback.trim();
        return value.isBlank() ? defaultValue : clip(value, 500);
    }

    private String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private String clean(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u0000', ' ').replaceAll("[\\t ]+", " ").trim();
    }

    private String cleanText(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u0000', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String clip(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private record Heuristic(String ownerName, String targetRole, String city, String phone, String email,
                             String education, String school, String major, Integer graduationYear,
                             List<String> skills, List<ProjectItem> projects, List<ExperienceItem> experiences,
                             String selfEvaluation) {}

    private record Merged(String ownerName, String targetRole, String city, String phone, String email,
                          String education, String school, String major, Integer graduationYear,
                          List<String> skills, List<ProjectItem> projects, List<ExperienceItem> experiences,
                          String selfEvaluation) {}

    public record ModelDraft(String ownerName, String targetRole, String city, String phone, String email,
                             String education, String school, String major, Integer graduationYear,
                             List<String> skills, List<ProjectItem> projects, List<ExperienceItem> experiences,
                             String selfEvaluation) {}
}
