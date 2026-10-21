package com.careernav.controller;
import com.careernav.common.ApiResponse;
import com.careernav.common.BusinessException;
import com.careernav.domain.ApiModels.DiagnosisReport;
import com.careernav.service.DiagnosisService;
import com.careernav.service.LangGraphJobAgentClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/diagnoses")
public class DiagnosisController {private final DiagnosisService service;private final LangGraphJobAgentClient jobAgent;public DiagnosisController(DiagnosisService service,LangGraphJobAgentClient jobAgent){this.service=service;this.jobAgent=jobAgent;}@GetMapping public ApiResponse<List<DiagnosisReport>> list(@RequestParam(required=false)Long resumeId){return ApiResponse.ok(service.list(resumeId));}@PostMapping public ApiResponse<DiagnosisReport> diagnose(@RequestBody com.careernav.domain.ApiModels.DiagnosisRequest request){return ApiResponse.ok("简历诊断完成",service.diagnose(request.resumeId(),request.jobId()));}@GetMapping("/latest/{resumeId}")public ApiResponse<DiagnosisReport> latest(@PathVariable Long resumeId){return ApiResponse.ok(service.latest(resumeId));}
    /** 简历 -> 岗位推荐 Agent：自主检索 + 算法精排 + 反思重试，返回推荐岗位与技能缺口。 */
    /** 纯规则诊断（供 LangGraph 诊断 Agent 作为工具调用，不触发 Agent）。 */
    @PostMapping("/rule")public ApiResponse<DiagnosisReport> rule(@RequestBody com.careernav.domain.ApiModels.DiagnosisRequest request){return ApiResponse.ok("规则诊断完成",service.previewRuleBased(request.resumeId(),request.jobId()));}
    @PostMapping("/agent")public ApiResponse<JsonNode> agentRecommend(@RequestParam Long resumeId,@RequestParam(defaultValue="")String city,@RequestParam(defaultValue="")String excludedCities,@RequestParam(defaultValue="8")int limit){JsonNode result=jobAgent.recommend(resumeId,city,excludedCities,limit);if(result==null){throw new BusinessException("岗位推荐 Agent 当前不可用，请确认 LangGraph 服务（8001）已启动");}return ApiResponse.ok("岗位推荐 Agent 执行完成",result);}}
