package com.careernav.service;

import com.careernav.domain.ApiModels.CareerEdge;
import com.careernav.domain.ApiModels.CareerFamilyMap;
import com.careernav.domain.ApiModels.CareerLevelCount;
import com.careernav.domain.ApiModels.CareerMap;
import com.careernav.domain.ApiModels.CareerRoleMap;
import com.careernav.domain.ApiModels.CareerRoleJobs;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.common.BusinessException;
import com.careernav.entity.JobEntity;
import com.careernav.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CareerMapService {
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final List<String> LEVEL_ORDER = List.of("初级", "中级", "高级", "架构师/技术管理", "未知");
    private final JobRepository repository;
    private final JobService jobService;
    private final JsonCodec jsonCodec;
    private final AtomicReference<CachedMap> cache = new AtomicReference<>();

    private record CachedMap(CareerMap map, Instant expiresAt) {}
    private record RoleDef(String id, String name, String familyId, String familyName,
                           List<String> keywords, List<String> skills, List<String> nextRoles) {}
    private record FamilyDef(String id, String name, String summary) {}

    private static final List<FamilyDef> FAMILIES = List.of(
            new FamilyDef("development", "研发工程", "实现产品功能、接口、客户端和底层软件。"),
            new FamilyDef("data_ai", "数据与人工智能", "处理数据、训练模型，并把 AI 能力落到产品中。"),
            new FamilyDef("quality", "测试与质量", "通过自动化、性能和验证保障软件交付质量。"),
            new FamilyDef("infra_security", "基础设施与安全", "维护系统可靠性、云平台、网络和安全边界。"),
            new FamilyDef("product_solution", "产品与解决方案", "连接业务、客户和技术交付。"),
            new FamilyDef("architecture_mgmt", "架构与管理", "负责系统演进、技术决策和研发组织。")
    );

    private static final List<RoleDef> ROLES = List.of(
            new RoleDef("architect", "架构师", "architecture_mgmt", "架构与管理",
                    List.of("架构师", "architect", "principal engineer", "技术专家"),
                    List.of("系统设计", "架构演进", "技术治理"), List.of("tech_lead", "engineering_manager")),
            new RoleDef("engineering_manager", "研发经理/技术负责人", "architecture_mgmt", "架构与管理",
                    List.of("研发经理", "技术经理", "工程经理", "技术负责人", "技术总监", "cto"),
                    List.of("团队管理", "交付管理", "技术决策"), List.of("architect", "product")),
            new RoleDef("ai_app", "AI 应用工程师", "data_ai", "数据与人工智能",
                    List.of("大模型", "llm", "rag", "agent", "aigc", "生成式", "ai应用", "ai", "人工智能"),
                    List.of("Python", "模型 API", "RAG", "Agent", "效果评估"), List.of("algorithm", "backend", "product")),
            new RoleDef("algorithm", "算法/机器学习工程师", "data_ai", "数据与人工智能",
                    List.of("算法", "machine learning", "机器学习", "深度学习", "nlp", "computer vision", "推荐算法", "搜索算法", "data scientist"),
                    List.of("Python", "机器学习", "模型评估", "数据处理"), List.of("ai_app", "data_engineering", "architect")),
            new RoleDef("data_engineering", "数据开发/数据平台", "data_ai", "数据与人工智能",
                    List.of("数据开发", "数据仓库", "etl", "数据平台", "data engineer", "数据治理"),
                    List.of("SQL", "数据仓库", "数据平台", "ETL"), List.of("data_analysis", "backend", "architect")),
            new RoleDef("data_analysis", "数据分析/BI", "data_ai", "数据与人工智能",
                    List.of("数据分析", "data analyst", "business intelligence", "bi工程师", "商业分析"),
                    List.of("SQL", "数据可视化", "统计基础", "指标管理"), List.of("data_engineering", "product")),
            new RoleDef("testdev", "测试开发/自动化测试", "quality", "测试与质量",
                    List.of("测试开发", "自动化测试", "测试平台", "sdet", "quality engineer", "测试工程师"),
                    List.of("自动化测试", "接口测试", "CI/CD", "质量度量"), List.of("qa", "devops", "architect")),
            new RoleDef("qa", "测试/质量工程师", "quality", "测试与质量",
                    List.of("功能测试", "qa", "测试验收", "质量保障"),
                    List.of("测试用例", "缺陷管理", "接口测试", "质量意识"), List.of("testdev", "product")),
            new RoleDef("security", "安全工程师", "infra_security", "基础设施与安全",
                    List.of("安全工程师", "渗透测试", "安全运营", "应用安全", "数据安全", "security engineer", "等保"),
                    List.of("安全测试", "威胁建模", "风险管理", "合规"), List.of("devops", "architect")),
            new RoleDef("devops", "DevOps/SRE/运维", "infra_security", "基础设施与安全",
                    List.of("devops", "sre", "site reliability", "运维", "kubernetes", "k8s", "平台工程", "中间件"),
                    List.of("Linux", "Kubernetes", "CI/CD", "可观测性"), List.of("cloud", "security", "architect")),
            new RoleDef("cloud", "云平台工程师", "infra_security", "基础设施与安全",
                    List.of("云计算", "cloud engineer", "aws", "azure", "gcp", "云平台"),
                    List.of("云平台", "容器", "基础设施即代码", "成本管理"), List.of("devops", "architect")),
            new RoleDef("network", "网络工程师", "infra_security", "基础设施与安全",
                    List.of("网络工程师", "network engineer", "网络运维", "tcp/ip"),
                    List.of("网络协议", "路由交换", "网络安全", "故障排查"), List.of("devops", "security")),
            new RoleDef("dba", "数据库工程师", "infra_security", "基础设施与安全",
                    List.of("dba", "database administrator", "数据库管理", "数据库工程师"),
                    List.of("SQL", "数据库调优", "备份恢复", "高可用"), List.of("backend", "data_engineering", "architect")),
            new RoleDef("presales", "售前/解决方案工程师", "product_solution", "产品与解决方案",
                    List.of("售前", "解决方案工程师", "solution engineer", "方案架构"),
                    List.of("方案设计", "客户沟通", "业务理解", "技术表达"), List.of("product", "project", "architect")),
            new RoleDef("implementation", "实施/技术支持工程师", "product_solution", "产品与解决方案",
                    List.of("实施工程师", "技术支持", "客户成功", "implementation engineer", "technical support"),
                    List.of("部署交付", "问题排查", "客户沟通", "文档"), List.of("presales", "project", "devops")),
            new RoleDef("product", "产品经理", "product_solution", "产品与解决方案",
                    List.of("产品经理", "product manager", "产品设计", "产品运营"),
                    List.of("需求分析", "用户研究", "产品规划", "数据分析"), List.of("project", "presales")),
            new RoleDef("project", "项目经理/交付经理", "product_solution", "产品与解决方案",
                    List.of("项目经理", "project manager", "交付经理", "项目群经理", "项目管理"),
                    List.of("计划管理", "风险管理", "跨团队协作", "交付"), List.of("product", "engineering_manager")),
            new RoleDef("game", "游戏开发", "development", "研发工程",
                    List.of("游戏开发", "unity", "unreal", "cocos", "渲染", "game engineer"),
                    List.of("C++/C#", "游戏引擎", "图形学", "性能优化"), List.of("frontend", "architect")),
            new RoleDef("embedded", "嵌入式/底层开发", "development", "研发工程",
                    List.of("嵌入式", "单片机", "固件", "驱动开发", "embedded", "firmware", "adas", "autosar", "车载", "车辆", "can总线"),
                    List.of("C/C++", "操作系统", "硬件接口", "实时系统"), List.of("backend", "architect")),
            new RoleDef("mobile", "移动端开发", "development", "研发工程",
                    List.of("android", "ios", "flutter", "react native", "移动端", "客户端开发"),
                    List.of("Android/iOS", "移动端架构", "性能优化", "发布流程"), List.of("frontend", "fullstack")),
            new RoleDef("frontend", "前端开发", "development", "研发工程",
                    List.of("前端", "frontend", "react", "vue", "javascript", "typescript", "web开发"),
                    List.of("HTML/CSS", "JavaScript", "Vue/React", "浏览器调试"), List.of("fullstack", "mobile", "product")),
            new RoleDef("fullstack", "全栈开发", "development", "研发工程",
                    List.of("全栈", "full stack", "fullstack"),
                    List.of("前端", "后端", "数据库", "接口设计"), List.of("backend", "frontend", "architect")),
            new RoleDef("backend", "后端开发", "development", "研发工程",
                    List.of("后端", "backend", "java", "spring", "golang", "go开发", "php", "node.js", ".net", "c++开发", "python", "c#", "c++", "爬虫", "数据采集"),
                    List.of("Java/Go/Python", "接口设计", "数据库", "缓存", "消息队列"), List.of("fullstack", "devops", "architect"))
    );

    public CareerMapService(JobRepository repository, JobService jobService, JsonCodec jsonCodec) {
        this.repository = repository;
        this.jobService = jobService;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(readOnly = true)
    public CareerRoleJobs jobsForRole(String roleId, String city, int page, int size) {
        RoleDef role = ROLES.stream().filter(item -> item.id().equals(roleId)).findFirst()
                .orElseThrow(() -> new BusinessException("\u804c\u4e1a\u65b9\u5411\u4e0d\u5b58\u5728\uff1a" + roleId));
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<JobEntity> matched = repository.findAll().stream()
                .filter(item -> "ACTIVE".equalsIgnoreCase(item.getStatus()))
                .filter(item -> {
                    RoleDef classified = classify(item);
                    return classified != null && role.id().equals(classified.id());
                })
                .filter(item -> value(city).isBlank() || value(city).equalsIgnoreCase(value(item.getCity())))
                .toList();
        int from = Math.min(matched.size(), safePage * safeSize);
        int to = Math.min(matched.size(), from + safeSize);
        List<JobView> items = matched.subList(from, to).stream().map(jobService::toView).toList();
        return new CareerRoleJobs(role.id(), role.name(), role.familyName(), matched.size(),
                safePage, safeSize, items);
    }

    @Transactional(readOnly = true)
    public CareerMap get() {
        CachedMap current = cache.get();
        if (current != null && current.expiresAt().isAfter(Instant.now())) return current.map();
        CareerMap generated = classifyAll();
        cache.set(new CachedMap(generated, Instant.now().plus(CACHE_TTL)));
        return generated;
    }

    private CareerMap classifyAll() {
        List<JobEntity> jobs = repository.findAll();
        Map<String, List<JobEntity>> buckets = new LinkedHashMap<>();
        Map<String, Map<String, Long>> levels = new LinkedHashMap<>();
        long classified = 0;
        long activeTotal = 0;
        for (JobEntity job : jobs) {
            if (job.getStatus() != null && !"ACTIVE".equalsIgnoreCase(job.getStatus())) continue;
            activeTotal++;
            RoleDef role = classify(job);
            String roleId = role == null ? "other" : role.id();
            buckets.computeIfAbsent(roleId, key -> new ArrayList<>()).add(job);
            String level = estimateLevel(job);
            levels.computeIfAbsent(roleId, key -> new LinkedHashMap<>()).merge(level, 1L, Long::sum);
            if (role != null) classified++;
        }
        List<CareerFamilyMap> families = new ArrayList<>();
        for (FamilyDef family : FAMILIES) {
            List<CareerRoleMap> roles = ROLES.stream()
                    .filter(role -> role.familyId().equals(family.id()))
                    .map(role -> roleMap(role, buckets.getOrDefault(role.id(), List.of()), levels.getOrDefault(role.id(), Map.of())))
                    .filter(role -> role.jobCount() > 0)
                    .toList();
            families.add(new CareerFamilyMap(family.id(), family.name(), family.summary(), roles));
        }
        List<CareerRoleMap> otherRoles = new ArrayList<>();
        if (!buckets.getOrDefault("other", List.of()).isEmpty()) {
            otherRoles.add(new CareerRoleMap("other", "其他/待分类", "other", "其他岗位",
                    buckets.get("other").size(), levelCounts(levels.getOrDefault("other", Map.of())),
                    List.of(), List.of(), buckets.get("other").stream().limit(3).map(jobService::toView).toList(), ""));
        }
        if (!otherRoles.isEmpty()) families.add(new CareerFamilyMap("other", "其他岗位", "暂未匹配到主要职业方向的岗位。", otherRoles));
        List<CareerEdge> edges = new ArrayList<>();
        for (RoleDef role : ROLES) {
            for (String next : role.nextRoles()) {
                edges.add(new CareerEdge(role.id(), next, "发展"));
            }
        }
        return new CareerMap(activeTotal, classified, families, edges);
    }

    private CareerRoleMap roleMap(RoleDef role, List<JobEntity> jobs, Map<String, Long> levels) {
        List<JobEntity> samples = jobs.stream().limit(3).toList();
        return new CareerRoleMap(role.id(), role.name(), role.familyId(), role.familyName(), jobs.size(),
                levelCounts(levels), role.skills(), role.nextRoles(),
                samples.stream().map(jobService::toView).toList(), role.keywords().stream().findFirst().orElse(""));
    }

    private List<CareerLevelCount> levelCounts(Map<String, Long> counts) {
        return LEVEL_ORDER.stream().map(level -> new CareerLevelCount(level, counts.getOrDefault(level, 0L))).toList();
    }

    private String estimateLevel(JobEntity job) {
        String title = value(job.getTitle()).toLowerCase(Locale.ROOT);
        String experience = value(job.getExperience()).toLowerCase(Locale.ROOT);
        if (containsAny(title, "架构师", "architect", "技术专家", "principal")) return "架构师/技术管理";
        if (containsAny(title, "技术经理", "研发经理", "工程经理", "技术负责人", "总监", "cto")) return "架构师/技术管理";
        if (containsAny(title, "高级", "资深", "senior", "lead")) return "高级";
        if (containsAny(title, "中级", "mid-level")) return "中级";
        if (containsAny(title, "初级", "实习", "应届", "junior", "校招", "intern")) return "初级";
        if (containsAny(experience, "10年以上", "5-10年", "5年以上")) return "高级";
        if (containsAny(experience, "3-5年")) return "中级";
        if (containsAny(experience, "1-3年", "1年以内")) return "初级";
        return "未知";
    }

    private RoleDef classify(JobEntity job) {
        String title = value(job.getTitle()).toLowerCase(Locale.ROOT);
        if (isNonTechnicalTitle(title)) return null;
        RoleDef titleRole = classifyTitle(title);
        if (titleRole != null) return titleRole;
        String skills = String.join(" ", jsonCodec.readList(job.getSkillsJson(), String.class)).toLowerCase(Locale.ROOT);
        String description = (value(job.getDescription()) + " " + value(job.getJobDescription()) + " " + value(job.getJobRequirements()) + " " + value(job.getCategory())).toLowerCase(Locale.ROOT);
        RoleDef best = null;
        int bestScore = 0;
        for (RoleDef role : ROLES) {
            int score = 0;
            for (String keyword : role.keywords()) {
                String key = keyword.toLowerCase(Locale.ROOT);
                if (skills.contains(key)) score += 3;
                if (description.contains(key)) score += 1;
            }
            if (score > bestScore) {
                best = role;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private RoleDef classifyTitle(String title) {
        if (containsAny(title, "\u6e38\u620f\u5f00\u53d1", "unity", "unreal", "cocos")) return roleById("game");
        if (containsAny(title, "\u5d4c\u5165\u5f0f", "\u5355\u7247\u673a", "\u56fa\u4ef6", "\u9a71\u52a8\u5f00\u53d1", "adas", "autosar")) return roleById("embedded");
        boolean fullstack = containsAny(title, "\u5168\u6808", "full stack", "fullstack");
        boolean frontend = containsAny(title, "\u524d\u7aef", "frontend", "web\u524d\u7aef");
        boolean backend = containsAny(title, "\u540e\u7aef", "backend", "java", "python", "golang", "go\u5f00\u53d1", "php", "node.js", ".net", "c#", "c++");
        if (fullstack || (frontend && backend)) return roleById("fullstack");
        if (frontend) return roleById("frontend");
        if (backend) return roleById("backend");
        for (String roleId : List.of("engineering_manager", "architect", "security", "devops", "cloud", "network", "dba",
                "game", "embedded", "mobile", "fullstack", "frontend", "backend", "ai_app", "algorithm",
                "testdev", "qa", "data_engineering", "data_analysis", "presales", "implementation", "product", "project")) {
            RoleDef role = ROLES.stream().filter(item -> item.id().equals(roleId)).findFirst().orElse(null);
            if (role != null && containsAny(title, role.keywords().toArray(String[]::new))) return role;
        }
        return null;
    }

    private boolean isNonTechnicalTitle(String title) {
        return containsAny(title, "\u524d\u53f0", "\u63a5\u5f85", "\u6587\u804c", "\u529e\u516c\u5ba4", "\u5ba2\u670d", "\u9500\u552e", "\u62db\u8058\u4e13\u5458", "\u4eba\u4e8b", "\u8bb2\u5e08");
    }

    private RoleDef roleById(String id) {
        return ROLES.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private String value(String value) { return value == null ? "" : value; }
}
