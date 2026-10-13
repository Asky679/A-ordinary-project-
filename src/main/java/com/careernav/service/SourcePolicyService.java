package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.config.AppProperties;
import com.careernav.entity.JobSourcePolicyEntity;
import com.careernav.repository.JobSourcePolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class SourcePolicyService {
    private static final List<String> BLOCK_PAGE_SIGNALS = List.of(
            "请输入验证码", "请完成安全验证", "登录后查看", "请先登录", "滑块验证",
            "verify you are human", "access denied", "人机验证", "访问过于频繁"
    );
    private final JobSourcePolicyRepository repository;
    private final AppProperties properties;

    public SourcePolicyService(JobSourcePolicyRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public void initializeDefaults() {
        if (repository.findBySourceCode("remote-mcp").isEmpty()) {
            JobSourcePolicyEntity policy = base("remote-mcp", "远程 MCP 岗位工具", "MCP");
            policy.setEnabled(properties.getMcp().isEnabled());
            policy.setStatus(policy.isEnabled() ? "READY" : "DISABLED");
            policy.setBaseUrl(properties.getMcp().getEndpoint());
            policy.setTermsUrl("请填写 MCP 数据服务授权协议地址");
            repository.save(policy);
        }
        if (repository.findBySourceCode("authorized-json-feed").isEmpty()) {
            JobSourcePolicyEntity policy = base("authorized-json-feed", "授权岗位 JSON Feed", "JSON_FEED");
            policy.setTermsUrl("请填写已确认的数据授权或服务条款地址");
            repository.save(policy);
        }
        if (repository.findBySourceCode("boss-zhipin-public").isEmpty()) {
            JobSourcePolicyEntity policy = base("boss-zhipin-public", "BOSS直聘公开城市页", "BOSS_PUBLIC_HTML");
            policy.setEnabled(true);
            policy.setStatus("READY");
            policy.setBaseUrl("https://www.zhipin.com/changsha/");
            policy.setFeedUrl("https://www.zhipin.com/changsha/");
            policy.setTermsUrl("https://about.zhipin.com/agreement?id=registerprotocol");
            policy.setCrawlDelayMs(5000L);
            policy.setRequestIntervalMs(5000L);
            repository.save(policy);
        }
        if (repository.findBySourceCode("authorized-csv").isEmpty()) {
            JobSourcePolicyEntity policy = base("authorized-csv", "授权 CSV 导入", "CSV");
            policy.setEnabled(true);
            policy.setStatus("READY");
            policy.setTermsUrl("由数据提供方授权后导入");
            repository.save(policy);
        }
    }

    private JobSourcePolicyEntity base(String code, String name, String type) {
        LocalDateTime now = LocalDateTime.now();
        JobSourcePolicyEntity policy = new JobSourcePolicyEntity();
        policy.setSourceCode(code);
        policy.setSourceName(name);
        policy.setSourceType(type);
        policy.setPolicyVersion("2026.10-v1");
        policy.setEnabled(false);
        policy.setBlocked(false);
        policy.setStatus("DISABLED");
        policy.setCrawlDelayMs(0L);
        policy.setRequestIntervalMs(properties.getCollection().getDefaultRequestIntervalMs());
        policy.setPolicyReviewedAt(now);
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);
        return policy;
    }

    @Transactional(readOnly = true)
    public List<JobSourcePolicyEntity> list() {
        return repository.findAllByOrderBySourceNameAsc();
    }

    @Transactional(readOnly = true)
    public JobSourcePolicyEntity require(String sourceCode) {
        return repository.findBySourceCode(sourceCode)
                .orElseThrow(() -> new BusinessException("数据来源策略不存在：" + sourceCode));
    }

    @Transactional(readOnly = true)
    public void assertAllowed(String sourceCode) {
        JobSourcePolicyEntity policy = require(sourceCode);
        if (!policy.isEnabled()) throw new BusinessException("数据来源未启用：" + policy.getSourceName());
        if (policy.isBlocked()) throw new BusinessException("数据来源已被阻断：" + policy.getBlockReason());
        if (policy.getNextAllowedAt() != null && policy.getNextAllowedAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException("数据来源处于退避期，下次允许访问：" + policy.getNextAllowedAt());
        }
    }

    public void enforceAllowedUrl(JobSourcePolicyEntity policy, String url) {
        if (policy.getBaseUrl() == null || policy.getBaseUrl().isBlank()) {
            throw new BusinessException("数据来源未配置授权域名：" + policy.getSourceName());
        }
        try {
            URI allowed = URI.create(policy.getBaseUrl());
            URI target = URI.create(url);
            if (allowed.getHost() == null || target.getHost() == null
                    || !allowed.getHost().equalsIgnoreCase(target.getHost())) {
                throw new BusinessException("目标域名不在来源白名单中：" + target.getHost());
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("来源 URL 配置无效");
        }
    }

    public long effectiveIntervalMs(JobSourcePolicyEntity policy) {
        long interval = Math.max(policy.getRequestIntervalMs() == null ? 0 : policy.getRequestIntervalMs(),
                policy.getCrawlDelayMs() == null ? 0 : policy.getCrawlDelayMs());
        interval = Math.max(interval, properties.getCollection().getDefaultRequestIntervalMs());
        interval = Math.min(interval, properties.getCollection().getMaxRequestIntervalMs());
        return Math.max(2000, interval);
    }

    @Transactional
    public void markRequestStarted(String sourceCode) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setLastCrawledAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        repository.save(policy);
    }

    @Transactional
    public void markSuccess(String sourceCode) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setLastSuccessAt(LocalDateTime.now());
        policy.setStatus("HEALTHY");
        policy.setBlockReason(null);
        policy.setUpdatedAt(LocalDateTime.now());
        repository.save(policy);
    }

    @Transactional
    public void markFullScan(String sourceCode) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setLastFullScanAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        repository.save(policy);
    }

    @Transactional
    public void markHttpStatus(String sourceCode, int status, String retryAfter) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setUpdatedAt(LocalDateTime.now());
        if (status == 403) {
            policy.setEnabled(false);
            policy.setBlocked(true);
            policy.setStatus("BLOCKED");
            policy.setBlockReason("收到 HTTP 403，已自动停止该来源");
        } else if (status == 429) {
            policy.setStatus("RATE_LIMITED");
            policy.setNextAllowedAt(LocalDateTime.now().plusSeconds(parseRetryAfter(retryAfter)));
            policy.setBlockReason("收到 HTTP 429，进入退避期");
        } else if (status >= 500) {
            policy.setStatus("DEGRADED");
            policy.setNextAllowedAt(LocalDateTime.now().plusHours(6));
            policy.setBlockReason("来源服务异常，暂停 6 小时");
        }
        repository.save(policy);
    }

    @Transactional
    public void inspectContent(String sourceCode, String body) {
        if (body == null || body.isBlank()) return;
        String visible = body
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT);
        boolean risky = BLOCK_PAGE_SIGNALS.stream().anyMatch(visible::contains);
        if (!risky) return;
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setEnabled(false);
        policy.setBlocked(true);
        policy.setStatus("BLOCKED");
        policy.setBlockReason("检测到登录、验证码或访问受限页面，已立即停止该来源");
        policy.setUpdatedAt(LocalDateTime.now());
        repository.save(policy);
        throw new BusinessException(policy.getBlockReason());
    }

    @Transactional
    public JobSourcePolicyEntity setEnabled(String sourceCode, boolean enabled) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setEnabled(enabled);
        policy.setBlocked(false);
        policy.setBlockReason(null);
        policy.setStatus(enabled ? "READY" : "DISABLED");
        policy.setNextAllowedAt(null);
        policy.setUpdatedAt(LocalDateTime.now());
        return repository.save(policy);
    }

    @Transactional
    public JobSourcePolicyEntity configureFeed(String sourceCode, String feedUrl, long intervalMs) {
        JobSourcePolicyEntity policy = require(sourceCode);
        policy.setBaseUrl(feedUrl);
        policy.setFeedUrl(feedUrl);
        policy.setPolicyReviewedAt(LocalDateTime.now());
        policy.setRequestIntervalMs(Math.max(2000, intervalMs));
        policy.setUpdatedAt(LocalDateTime.now());
        return repository.save(policy);
    }

    private long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return 86400;
        try { return Math.max(60, Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return 86400; }
    }
}
