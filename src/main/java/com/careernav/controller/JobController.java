package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.*;import com.careernav.domain.JobImport;import com.careernav.service.*;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/jobs")
public class JobController {private final JobService jobs;private final ResumeService resumes;private final McpJobGateway mcp;public JobController(JobService jobs,ResumeService resumes,McpJobGateway mcp){this.jobs=jobs;this.resumes=resumes;this.mcp=mcp;}@GetMapping public ApiResponse<List<JobView>> list(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String city,@RequestParam(defaultValue="200")int limit){return ApiResponse.ok(jobs.list(keyword,city,limit));}@GetMapping("/recommend")public ApiResponse<List<JobRecommendation>> recommend(
            @RequestParam Long resumeId,
            @RequestParam(defaultValue="") String city,
            @RequestParam(defaultValue="6") int limit,
            @RequestParam(defaultValue="") String excludedCities,
            @RequestParam(defaultValue="") String category,
            @RequestParam(defaultValue="") String experience,
            @RequestParam(defaultValue="") String education,
            @RequestParam(defaultValue="0") int salaryMin,
            @RequestParam(defaultValue="") String industry){
        List<String> excluded=Arrays.stream(excludedCities.split("[,，]")).map(String::trim).filter(value->!value.isBlank()).toList();
        JobService.JobFilter filter=new JobService.JobFilter(category,experience,education,salaryMin,industry);
        // 规则分只用于召回与兜底，最终匹配度由 Agent 精排
        return ApiResponse.ok(jobs.recommend(resumes.get(resumeId),city,limit,JobService.MatchWeights.defaults(),excluded,filter));
    }
    /** Agent 精排：对候选岗位按岗位自身要求重新打分，并与规则分融合。 */
    @PostMapping("/recommend/agent")
    public ApiResponse<AgentMatchResponse> agentRerank(@RequestBody AgentMatchRequest request){
        return ApiResponse.ok(jobs.agentRerank(resumes.get(request.resumeId()),request.jobIds()));
    }
    @GetMapping("/{id}/similar")public ApiResponse<List<JobView>> similar(@PathVariable Long id,@RequestParam(defaultValue="6")int limit){return ApiResponse.ok(jobs.similar(id,limit));}
    @GetMapping("/{id}")public ApiResponse<JobView> get(@PathVariable Long id){return ApiResponse.ok(jobs.get(id));}@PostMapping("/refresh")public ApiResponse<List<JobView>> refresh(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String city,@RequestParam(defaultValue="30")int limit){List<JobImport> imported=mcp.fetchJobs(keyword,city,limit);if(imported.isEmpty())return ApiResponse.ok("MCP 未配置或当前无新增数据，已保留本地岗位库",List.of());return ApiResponse.ok("已从 MCP 同步岗位",jobs.importJobs(imported));}}
