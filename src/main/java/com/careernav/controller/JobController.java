package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.*;import com.careernav.domain.JobImport;import com.careernav.service.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/jobs")
public class JobController {private final JobService jobs;private final ResumeService resumes;private final McpJobGateway mcp;public JobController(JobService jobs,ResumeService resumes,McpJobGateway mcp){this.jobs=jobs;this.resumes=resumes;this.mcp=mcp;}@GetMapping public ApiResponse<List<JobView>> list(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String city,@RequestParam(defaultValue="200")int limit){return ApiResponse.ok(jobs.list(keyword,city,limit));}@GetMapping("/recommend")public ApiResponse<List<JobRecommendation>> recommend(
            @RequestParam Long resumeId,
            @RequestParam(defaultValue="") String city,
            @RequestParam(defaultValue="6") int limit,
            @RequestParam(defaultValue="35") int skillWeight,
            @RequestParam(defaultValue="20") int roleWeight,
            @RequestParam(defaultValue="15") int experienceWeight,
            @RequestParam(defaultValue="10") int educationWeight,
            @RequestParam(defaultValue="5") int cityWeight,
            @RequestParam(defaultValue="5") int evidenceWeight,
            @RequestParam(defaultValue="") String excludedCities){
        List<String> excluded=Arrays.stream(excludedCities.split("[,，]")).map(String::trim).filter(value->!value.isBlank()).toList();
        JobService.MatchWeights weights=new JobService.MatchWeights(skillWeight,roleWeight,experienceWeight,educationWeight,cityWeight,evidenceWeight);
        return ApiResponse.ok(jobs.recommend(resumes.get(resumeId),city,limit,weights,excluded));
    }@PostMapping("/refresh")public ApiResponse<List<JobView>> refresh(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String city,@RequestParam(defaultValue="30")int limit){List<JobImport> imported=mcp.fetchJobs(keyword,city,limit);if(imported.isEmpty())return ApiResponse.ok("MCP 未配置或当前无新增数据，已保留本地岗位库",List.of());return ApiResponse.ok("已从 MCP 同步岗位",jobs.importJobs(imported));}}
