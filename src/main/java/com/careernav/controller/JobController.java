package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.AgentMatchRequest;
import com.careernav.domain.ApiModels.AgentMatchResponse;
import com.careernav.domain.ApiModels.AhpWeightProfile;
import com.careernav.domain.ApiModels.CityJobCount;
import com.careernav.domain.ApiModels.CityJobPage;
import com.careernav.domain.ApiModels.JobRecommendation;
import com.careernav.domain.ApiModels.JobView;
import com.careernav.domain.JobImport;
import com.careernav.service.JobService;
import com.careernav.service.McpJobGateway;
import com.careernav.service.ResumeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    private final ResumeService resumes;
    private final McpJobGateway mcp;

    public JobController(JobService jobs, ResumeService resumes, McpJobGateway mcp) {
        this.jobs = jobs;
        this.resumes = resumes;
        this.mcp = mcp;
    }

    @GetMapping
    public ApiResponse<List<JobView>> list(@RequestParam(defaultValue = "") String keyword,
                                           @RequestParam(defaultValue = "") String city,
                                           @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(jobs.list(keyword, city, limit));
    }

    /** 全局 AHP 六维基准权重、判断矩阵、主特征值和一致性比率。 */
    @GetMapping("/weights/ahp")
    public ApiResponse<AhpWeightProfile> ahpWeights() {
        return ApiResponse.ok(jobs.ahpProfile(null));
    }

    /** 所选岗位的实时 AHP 调整权重；六项归一化权重之和为 1。 */
    @GetMapping("/{id}/weights/ahp")
    public ApiResponse<AhpWeightProfile> jobAhpWeights(@PathVariable Long id) {
        return ApiResponse.ok(jobs.ahpProfile(id));
    }

    @GetMapping("/recommend")
    public ApiResponse<List<JobRecommendation>> recommend(
            @RequestParam Long resumeId,
            @RequestParam(defaultValue = "") String city,
            @RequestParam(defaultValue = "6") int limit,
            @RequestParam(defaultValue = "") String excludedCities,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String experience,
            @RequestParam(defaultValue = "") String education,
            @RequestParam(defaultValue = "0") int salaryMin,
            @RequestParam(defaultValue = "") String industry) {
        List<String> excluded = Arrays.stream(excludedCities.split("[,，]"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        JobService.JobFilter filter = new JobService.JobFilter(
                category, experience, education, salaryMin, industry);
        // null 表示使用 AHP 基准权重；Agent 精排只在其上做受限调权。
        return ApiResponse.ok(jobs.recommend(
                resumes.get(resumeId), city, limit, null, excluded, filter));
    }

    /** Agent 精排：读取岗位要求并返回受限权重，由规则引擎计算最终分。 */
    @PostMapping("/recommend/agent")
    public ApiResponse<AgentMatchResponse> agentRerank(@RequestBody AgentMatchRequest request) {
        return ApiResponse.ok(jobs.agentRerank(resumes.get(request.resumeId()), request.jobIds()));
    }

    @GetMapping("/cities")
    public ApiResponse<List<CityJobCount>> cityCounts() {
        return ApiResponse.ok(jobs.cityCounts());
    }

    /** 城市全量岗位库：分页返回指定城市的全部 ACTIVE 岗位。 */
    @GetMapping("/city")
    public ApiResponse<CityJobPage> cityJobs(
            @RequestParam Long resumeId,
            @RequestParam String city,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "60") int size,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String experience,
            @RequestParam(defaultValue = "") String education,
            @RequestParam(defaultValue = "0") int salaryMin,
            @RequestParam(defaultValue = "") String industry) {
        JobService.JobFilter filter = new JobService.JobFilter(
                category, experience, education, salaryMin, industry);
        return ApiResponse.ok(jobs.cityJobs(
                resumes.get(resumeId), city, keyword, sort, page, size, filter));
    }

    @GetMapping("/{id}/similar")
    public ApiResponse<List<JobView>> similar(@PathVariable Long id,
                                              @RequestParam(defaultValue = "6") int limit) {
        return ApiResponse.ok(jobs.similar(id, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobView> get(@PathVariable Long id) {
        return ApiResponse.ok(jobs.get(id));
    }

    @PostMapping("/refresh")
    public ApiResponse<List<JobView>> refresh(@RequestParam(defaultValue = "") String keyword,
                                              @RequestParam(defaultValue = "") String city,
                                              @RequestParam(defaultValue = "30") int limit) {
        List<JobImport> imported = mcp.fetchJobs(keyword, city, limit);
        if (imported.isEmpty()) {
            return ApiResponse.ok("MCP 未配置或当前无新增数据，已保留本地岗位库", List.of());
        }
        return ApiResponse.ok("已从 MCP 同步岗位", jobs.importJobs(imported));
    }
}