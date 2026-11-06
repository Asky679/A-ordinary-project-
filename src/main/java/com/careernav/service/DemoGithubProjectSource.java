package com.careernav.service;

import com.careernav.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Makes the sample repository usable in the demo profile when GitHub is rate limited. */
@Component
@Primary
@Profile("demo")
public class DemoGithubProjectSource extends GithubProjectSource {
    private static final String OWNER = "Asky679";
    private static final String REPO = "A-ordinary-project-";
    private static final String SNAPSHOT_SHA = "9f0aac110e0226a6c627831c7cfcff9d555896b3";

    public DemoGithubProjectSource(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String head(RepoRef ref) {
        return isSample(ref) ? SNAPSHOT_SHA : super.head(ref);
    }

    @Override
    public Map<String, String> javaSources(RepoRef ref, String sha) {
        if (!isSample(ref) || !SNAPSHOT_SHA.equals(sha)) return super.javaSources(ref, sha);
        try (InputStream input = getClass().getResourceAsStream("/demo-repository.zip")) {
            if (input == null) throw new BusinessException("演示仓库快照缺失");
            return extractJava(input.readAllBytes());
        } catch (IOException exception) {
            throw new BusinessException("读取演示仓库快照失败：" + exception.getMessage());
        }
    }

    private boolean isSample(RepoRef ref) {
        return OWNER.equalsIgnoreCase(ref.owner()) && REPO.equalsIgnoreCase(ref.repo());
    }
}
