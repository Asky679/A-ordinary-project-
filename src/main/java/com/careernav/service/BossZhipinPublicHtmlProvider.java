package com.careernav.service;

import com.careernav.domain.JobImport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class BossZhipinPublicHtmlProvider {
    public static final String SOURCE_CODE = "boss-zhipin-public";
    private static final String PUBLIC_CITY_URL = "https://www.zhipin.com/changsha/";

    private static final Set<String> TARGET_KEYWORDS = Set.of(
            "java", "后端", "前端", "python", "算法工程师", "软件工程师", "测试工程师",
            "开发工程师", "数据开发", "数据分析", "数据工程", "运维工程师", "全栈",
            "人工智能", "大模型", "嵌入式", "android", "安卓", "ios", "c++",
            "golang", "网络安全"
    );
    private static final Set<String> NEGATIVE_KEYWORDS = Set.of(
            "销售", "顾问", "包装", "质检", "普工", "主播", "客服", "选址", "房产",
            "中介", "文员", "驾驶", "养老", "护理", "保健", "保险", "招商", "运营专员"
    );

    private final CompliantHttpFetcher fetcher;
    public BossZhipinPublicHtmlProvider(CompliantHttpFetcher fetcher) { this.fetcher = fetcher; }

    public List<JobImport> fetch(int limit) {
        CompliantHttpFetcher.FetchResult response = fetcher.get(SOURCE_CODE, PUBLIC_CITY_URL);
        if (!response.successful()) {
            throw new com.careernav.common.BusinessException("BOSS 公开城市页返回 HTTP " + response.statusCode());
        }
        Document document = Jsoup.parse(response.body(), PUBLIC_CITY_URL);
        Elements cards = document.select("a.job-info[href^=/job_detail/]");
        List<JobImport> jobs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();
        for (Element card : cards) {
            if (jobs.size() >= limit) break;
            String href = card.attr("href");
            if (href.contains("?") || href.startsWith("/job_detail/l")) continue;
            String externalId = "boss-" + href.substring(href.lastIndexOf('/') + 1).replace(".html", "");
            if (!seen.add(externalId)) continue;
            String title = text(card, ".name");
            if (title.isBlank()) continue;
            Element listItem = card.closest("li");
            String company = listItem == null ? "" : text(listItem, "a.user-info .name");
            if (company.isBlank()) company = "BOSS直聘公开公司";
            Elements meta = card.select("p.job-text > span");
            String city = value(meta, 0, "长沙");
            String experience = value(meta, 1, "不限");
            String education = value(meta, 2, "不限");
            List<String> skills = new ArrayList<>();
            for (int i = 3; i < meta.size(); i++) {
                String skill = meta.get(i).text().trim();
                if (!skill.isBlank()) skills.add(skill);
            }
            String titleText = title.toLowerCase(java.util.Locale.ROOT);
            if (NEGATIVE_KEYWORDS.stream().anyMatch(titleText::contains)) continue;
            String relevanceText = (title + " " + String.join(" ", skills)).toLowerCase(java.util.Locale.ROOT);
            if (TARGET_KEYWORDS.stream().noneMatch(relevanceText::contains)) continue;
            String salary = text(card, ".salary");
            if (salary.isBlank()) salary = "面议";
            String companyType = listItem == null ? "" : text(listItem, ".sub-li-bottom-commany-info .type");
            String description = "BOSS直聘公开城市页岗位摘要"
                    + (companyType.isBlank() ? "" : "；公司行业：" + companyType)
                    + "；详细信息请访问原始来源链接。";
            jobs.add(new JobImport(externalId, title, company, city, salary, education, experience,
                    skills, List.of(), description, "BOSS直聘公开城市页",
                    "https://www.zhipin.com" + href, now,
                    null, now.plusDays(35), now, "ACTIVE", null, "2026.10-v1"));
        }
        return jobs;
    }

    private String text(Element root, String selector) {
        Element element = root.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    private String value(Elements elements, int index, String fallback) {
        if (index >= elements.size()) return fallback;
        String value = elements.get(index).text().trim();
        return value.isBlank() ? fallback : value;
    }
}
