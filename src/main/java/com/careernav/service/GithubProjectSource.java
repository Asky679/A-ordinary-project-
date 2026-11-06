package com.careernav.service;

import com.careernav.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class GithubProjectSource {
    private static final Pattern URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9._-]{1,100})/?$");
    private static final int MAX_ARCHIVE_BYTES = 15_000_000;
    private static final int MAX_SOURCE_BYTES = 2_000_000;
    private static final int MAX_FILES = 500;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper;

    public GithubProjectSource(ObjectMapper mapper) { this.mapper = mapper; }

    public RepoRef parse(String input) {
        Matcher match = URL.matcher(input == null ? "" : input.trim().replaceFirst("\\.git$", ""));
        if (!match.matches() || match.group(2).equals(".") || match.group(2).equals("..")) {
            throw new BusinessException("请输入公开 GitHub 仓库地址，例如 https://github.com/owner/repo");
        }
        return new RepoRef(match.group(1), match.group(2));
    }

    public String head(RepoRef ref) {
        try {
            // GitHub's Atom feed exposes the default branch's newest immutable commit without REST quota.
            String feedUrl = ref.url() + "/commits/HEAD.atom";
            HttpResponse<String> feed = http.send(request(feedUrl).header("Accept", "application/atom+xml").build(), HttpResponse.BodyHandlers.ofString());
            if (feed.statusCode() == 200) {
                Matcher commit = Pattern.compile("Grit::Commit/([a-fA-F0-9]{40})").matcher(feed.body());
                if (commit.find()) return commit.group(1);
            }
            String url = "https://api.github.com/repos/" + ref.owner() + "/" + ref.repo() + "/commits/HEAD";
            HttpRequest request = request(url).header("Accept", "application/vnd.github+json").build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw upstreamError(response.statusCode());
            JsonNode sha = mapper.readTree(response.body()).path("sha");
            if (!sha.isTextual() || !sha.asText().matches("[a-fA-F0-9]{40}")) throw new BusinessException("GitHub 返回的提交信息无效");
            return sha.asText();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException("读取 GitHub 仓库失败：" + e.getMessage());
        }
    }

    public Map<String, String> javaSources(RepoRef ref, String sha) {
        try {
            String url = "https://codeload.github.com/" + ref.owner() + "/" + ref.repo() + "/zip/" + sha;
            HttpResponse<InputStream> response = http.send(request(url).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw upstreamError(response.statusCode());
            }
            try (InputStream input = response.body()) {
                byte[] archive = readLimited(input, MAX_ARCHIVE_BYTES);
                return extractJava(archive);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException("下载 GitHub 源码失败：" + e.getMessage());
        }
    }

    Map<String, String> extractJava(byte[] archive) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        int totalSourceBytes = 0;
        int totalExpandedBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 5_000) throw new BusinessException("仓库文件过多，请选择较小的项目");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("(?i)^[a-z]:/.*") || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw new BusinessException("ZIP 包含不安全的文件路径");
                }
                String path = name.replaceFirst("^.*?/(?=src/)", "");
                if (!eligible(path)) {
                    totalExpandedBytes += drainLimited(zip, 25_000_000);
                    if (totalExpandedBytes > 50_000_000) throw new BusinessException("ZIP 解压后内容超过 50 MB");
                    continue;
                }
                if (files.size() >= MAX_FILES) throw new BusinessException("Java 源码文件超过 500 个，当前版本暂不支持");
                byte[] code = readLimited(zip, MAX_SOURCE_BYTES);
                totalSourceBytes += code.length;
                totalExpandedBytes += code.length;
                if (totalExpandedBytes > 50_000_000) throw new BusinessException("ZIP 解压后内容超过 50 MB");
                if (totalSourceBytes > 25_000_000) throw new BusinessException("Java 源码总量超过 25 MB，当前版本暂不支持");
                if (files.putIfAbsent(path, new String(code, java.nio.charset.StandardCharsets.UTF_8)) != null) {
                    throw new BusinessException("ZIP 中存在重复的 Java 源码路径");
                }
            }
        }
        if (files.isEmpty()) throw new BusinessException("仓库中没有可分析的 Java 业务源码");
        return files;
    }

    private boolean eligible(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".java") || lower.contains("../") || lower.startsWith("/")) return false;
        for (String part : lower.split("/")) {
            if (part.equals("target") || part.equals("build") || part.equals("generated") || part.equals(".mvn")
                    || part.equals("test") || part.equals("tests") || part.equals("node_modules") || part.equals("vendor")) return false;
        }
        return true;
    }

    private byte[] readLimited(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > max) throw new BusinessException("仓库或单个源码文件超过分析大小限制");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private int drainLimited(InputStream input, int max) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > max) throw new BusinessException("ZIP 中的单个文件解压后过大");
        }
        return total;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "CareerNav-ProjectInterview/1.0").GET();
    }

    private BusinessException upstreamError(int status) {
        return new BusinessException(status == 404 ? "仓库不存在或不是公开仓库" :
                status == 403 || status == 429 ? "GitHub 请求限额已用完，请稍后重试" : "GitHub 返回错误：" + status);
    }

    public record RepoRef(String owner, String repo) {
        public String url() { return "https://github.com/" + owner + "/" + repo; }
    }
}
