package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.config.AppProperties;
import com.careernav.entity.JobSourcePolicyEntity;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class CompliantHttpFetcher {
    private final SourcePolicyService policyService;
    private final AppProperties properties;
    private final HttpClient client;
    private final Map<String, ReentrantLock> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastRequestAt = new ConcurrentHashMap<>();

    public CompliantHttpFetcher(SourcePolicyService policyService, AppProperties properties) {
        this.policyService = policyService;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }

    public FetchResult get(String sourceCode, String url) {
        JobSourcePolicyEntity policy = policyService.require(sourceCode);
        policyService.assertAllowed(sourceCode);
        policyService.enforceAllowedUrl(policy, url);
        URI target = URI.create(url);
        String host = target.getHost().toLowerCase();
        ReentrantLock lock = hostLocks.computeIfAbsent(host, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            waitForInterval(policy, host);
            policyService.markRequestStarted(sourceCode);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", properties.getCollection().getUserAgent()
                            + " (+mailto:" + properties.getCollection().getContactEmail() + ")")
                    .header("Accept", "application/json, application/rss+xml, application/xml, text/html;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            lastRequestAt.put(host, Instant.now());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                policyService.markHttpStatus(sourceCode, status,
                        response.headers().firstValue("Retry-After").orElse(null));
                return new FetchResult(status, response.body(), null, null, target.toString());
            }
            policyService.inspectContent(sourceCode, response.body());
            policyService.markSuccess(sourceCode);
            return new FetchResult(status, response.body(),
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null), target.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("采集请求被中断");
        } catch (java.io.IOException exception) {
            throw new BusinessException("采集请求失败：" + exception.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void waitForInterval(JobSourcePolicyEntity policy, String host) {
        Instant previous = lastRequestAt.get(host);
        if (previous == null) return;
        long elapsed = Duration.between(previous, Instant.now()).toMillis();
        long waitMs = policyService.effectiveIntervalMs(policy) - elapsed;
        if (waitMs > 0) {
            try { Thread.sleep(waitMs); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待来源限速间隔时被中断");
            }
        }
    }

    public record FetchResult(int statusCode, String body, String etag,
                              String lastModified, String finalUrl) {
        public boolean successful() { return statusCode >= 200 && statusCode < 300; }
    }
}
