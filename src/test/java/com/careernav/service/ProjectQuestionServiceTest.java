package com.careernav.service;

import com.careernav.common.BusinessException;
import com.careernav.service.GithubProjectSource.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectQuestionServiceTest {
    @Test
    void uploadsRootlessZipAndContinuesWithGroundedFollowUp() throws Exception {
        byte[] archive = zip(Map.of(
                "src/main/java/demo/OrderService.java", "package demo; class OrderService {\n"
                        + " int create(int id) {\n if (id < 1) return 0;\n return save(id);\n }\n"
                        + " int save(int id) {\n return id + 1;\n }\n"
                        + " int update(int id) {\n return save(id + 2);\n }\n"
                        + " int remove(int id) {\n return id - 1;\n }\n }",
                "src/test/java/demo/OrderServiceTest.java", "class OrderServiceTest {}"));
        ProjectQuestionService service = new ProjectQuestionService(new GithubProjectSource(new ObjectMapper()), mock(AgentModelClient.class));
        var set = service.generateZip("orders.zip", archive, "学生项目：订单处理，没有线上用户");
        assertEquals(1, set.analyzedFiles());
        assertEquals(12, set.questions().size());
        assertTrue(set.repoUrl().startsWith("upload:"));
        assertTrue(set.questions().stream().allMatch(q -> q.evidence().url().isEmpty()
                && q.evidence().path().equals("src/main/java/demo/OrderService.java")));
        assertSame(set, service.generateZip("orders.zip", archive, "学生项目：订单处理，没有线上用户"));
        var followUp = service.followUp(set.repoUrl(), set.sha(), "学生项目：订单处理，没有线上用户",
                "A1-1", "这是我自己做的课程项目，我在本地测试了 id 等于 0 的情况");
        assertTrue(followUp.question().contains(set.questions().getFirst().evidence().symbol()));
    }

    @Test
    void rejectsUnsafeOrInvalidZipUpload() throws Exception {
        GithubProjectSource source = new GithubProjectSource(new ObjectMapper());
        ProjectQuestionService service = new ProjectQuestionService(source, mock(AgentModelClient.class));
        assertThrows(BusinessException.class, () -> service.generateZip("project.zip", "not a zip".getBytes(), ""));
        assertThrows(BusinessException.class, () -> service.generateZip("project.txt", zip(Map.of("src/main/java/A.java", "class A {}")), ""));
        byte[] traversal = zip(Map.of("../outside.java", "class Outside {}"));
        assertThrows(BusinessException.class, () -> service.generateZip("project.zip", traversal, ""));
    }

    private byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    @Test
    void demoRepositoryUsesBundledSourceSnapshot() {
        DemoGithubProjectSource source = new DemoGithubProjectSource(new ObjectMapper());
        RepoRef repo = source.parse("https://github.com/Asky679/A-ordinary-project-");
        String sha = source.head(repo);
        var files = source.javaSources(repo, sha);
        assertEquals("9f0aac110e0226a6c627831c7cfcff9d555896b3", sha);
        assertEquals(80, files.size());
        assertTrue(files.containsKey("src/main/java/com/careernav/service/InterviewService.java"));
    }

    @Test
    void generatesGroundedProgressiveQuestionsAndReusesCommitSnapshot() {
        GithubProjectSource source = mock(GithubProjectSource.class);
        AgentModelClient model = mock(AgentModelClient.class);
        RepoRef ref = new RepoRef("owner", "repo");
        String url = ref.url();
        String sha = "a".repeat(40);
        when(source.parse(url)).thenReturn(ref);
        when(source.head(ref)).thenReturn(sha);
        when(source.javaSources(ref, sha)).thenReturn(Map.of(
                "src/main/java/demo/OrderController.java", "package demo; class OrderController {\n"
                        + " @GetMapping public String get(int id) {\n if (id < 1) return \"bad\";\n return load(id);\n }\n"
                        + " String load(int id) {\n return String.valueOf(id);\n }\n}",
                "src/main/java/demo/OrderService.java", "package demo; class OrderService {\n"
                        + " String create(int id) {\n if (id < 1) throw new IllegalArgumentException();\n return save(id);\n }\n"
                        + " String save(int id) {\n return String.valueOf(id);\n }\n"
                        + " String update(int id) {\n return save(id + 1);\n }\n}"));
        ProjectQuestionService service = new ProjectQuestionService(source, model);
        var first = service.generate(url, "QPS 提升 3 倍");
        var second = service.generate(url, "QPS 提升 3 倍");
        assertSame(first, second);
        assertEquals(15, first.questions().size());
        assertEquals(5, first.questions().stream().filter(q -> q.depth() == 1).count());
        assertTrue(first.questions().stream().allMatch(q -> !q.signal().isBlank() && !q.answerChecks().isEmpty()));
        assertTrue(first.questions().stream().allMatch(q -> q.evidence().url().contains("/blob/" + sha + "/")));
        assertTrue(first.questions().stream().allMatch(q -> q.evidence().snippet().contains(q.evidence().startLine() + ": ")));
        var followUp = service.followUp(url, sha, "QPS 提升 3 倍", "A1-1", "我们做了很多优化，效果应该比较明显");
        assertEquals("本地规则", followUp.generationMode());
        assertEquals(first.questions().get(0).evidence(), followUp.evidence());
        assertTrue(followUp.question().contains(first.questions().get(0).evidence().symbol()));
        var metricFollowUp = service.followUp(url, sha, "QPS 提升 3 倍", "A1-1", "我改了岗位筛选和排序逻辑，QPS 提升了 3 倍");
        assertTrue(metricFollowUp.reason().contains("基线"));
        when(model.available()).thenReturn(true);
        when(model.generateJson(anyString(), eq(ProjectQuestionService.ModelFollowUp.class)))
                .thenReturn(new ProjectQuestionService.ModelFollowUp("不存在的源码", "凭空编造的问题？"));
        var rejected = service.followUp(url, sha, "QPS 提升 3 倍", "A1-1", "我按照具体输入逐步解释了实现，但还需要验证高并发行为");
        assertEquals("本地规则", rejected.generationMode());
        var student = service.followUp(url, sha, "QPS 提升 3 倍", "A1-1", "这是课程项目，没有线上故障，我只在本地模拟了错误返回");
        assertTrue(student.reason().contains("本地复现"));
        assertThrows(BusinessException.class, () -> service.followUp(url, "invalid", "QPS 提升 3 倍", "A1-1", "足够长的回答内容"));
        verify(source, times(1)).javaSources(ref, sha);
    }

    @Test
    void rejectsNonGithubHostsAndPathInjection() {
        GithubProjectSource source = new GithubProjectSource(new ObjectMapper());
        assertThrows(BusinessException.class, () -> source.parse("https://github.com.evil.test/a/b"));
        assertThrows(BusinessException.class, () -> source.parse("https://github.com/a/../b"));
        assertEquals("repo", source.parse("https://github.com/owner/repo.git").repo());
    }

    @Test
    void analyzesRealProjectSourcesWithoutInventingEvidence() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        Path root = Path.of("src/main/java");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                files.put("src/main/java/" + root.relativize(path).toString().replace('\\', '/'), Files.readString(path));
            }
        }
        ProjectQuestionService service = new ProjectQuestionService(mock(GithubProjectSource.class), mock(AgentModelClient.class));
        var snapshot = service.analyze(new RepoRef("Asky679", "A-ordinary-project-"), "a".repeat(40), files);
        assertEquals(5, snapshot.evidence().size());
        assertTrue(snapshot.evidence().stream().allMatch(e -> files.containsKey(e.path())));
        assertTrue(snapshot.evidence().stream().allMatch(e -> e.startLine() > 0 && e.endLine() >= e.startLine()));

        GithubProjectSource source = mock(GithubProjectSource.class);
        RepoRef repo = new RepoRef("Asky679", "A-ordinary-project-");
        when(source.parse(repo.url())).thenReturn(repo);
        when(source.head(repo)).thenReturn("a".repeat(40));
        when(source.javaSources(repo, "a".repeat(40))).thenReturn(files);
        var studentSet = new ProjectQuestionService(source, mock(AgentModelClient.class))
                .generate(repo.url(), "学生课程项目：简历管理、岗位匹配和模拟面试，没有线上用户或压测数据");
        var fault = studentSet.questions().stream().filter(q -> q.id().equals("A4-1")).findFirst().orElseThrow();
        assertFalse(fault.evidence().failure().isBlank());
        assertTrue(fault.question().contains(fault.evidence().failure()));
        var outcome = studentSet.questions().stream().filter(q -> q.id().equals("A5-1")).findFirst().orElseThrow();
        assertFalse(outcome.question().contains("QPS"));
        assertFalse(outcome.question().contains("压测"));
    }

    @Test
    void usesModelQuestionsOnlyWhenTheWholeFollowUpChainIsValid() {
        GithubProjectSource source = mock(GithubProjectSource.class);
        AgentModelClient model = mock(AgentModelClient.class);
        RepoRef repo = new RepoRef("owner", "repo");
        String code = "class Service {\n"
                + " int run(int n) {\n if (n < 1) throw new IllegalArgumentException();\n return n + 1;\n }\n"
                + " int other(int n) {\n if (n > 5) return n;\n return run(n);\n }\n"
                + " int third(int n) {\n return other(n + 1);\n }\n"
                + " int fourth(int n) {\n return third(n + 1);\n }\n }";
        when(source.parse(repo.url())).thenReturn(repo);
        when(source.head(repo)).thenReturn("b".repeat(40));
        when(source.javaSources(repo, "b".repeat(40))).thenReturn(Map.of("src/main/java/demo/Service.java", code));
        var baseline = new ProjectQuestionService(source, mock(AgentModelClient.class)).generate(repo.url(), "学生项目");
        String symbol = baseline.questions().getFirst().evidence().symbol();
        String quote = baseline.questions().getFirst().evidence().snippet().substring(0, 12);
        when(model.available()).thenReturn(true);
        when(model.generateJson(anyString(), eq(ProjectQuestionService.ModelBatch.class)))
                .thenReturn(new ProjectQuestionService.ModelBatch(java.util.List.of(
                        new ProjectQuestionService.ModelQuestion("A1-1", quote, symbol + " 的这个判断由谁设计？"),
                        new ProjectQuestionService.ModelQuestion("A1-2", quote, symbol + " 的这个判断如何影响输入？"))));
        var set = new ProjectQuestionService(source, model).generate(repo.url(), "学生项目");
        assertEquals("本地证据模板", set.generationMode());
        assertFalse(set.questions().getFirst().question().contains("这个判断由谁设计"));
    }
}
