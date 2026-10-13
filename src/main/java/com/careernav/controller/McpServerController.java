package com.careernav.controller;
import com.careernav.domain.ApiModels.JobView;import com.careernav.service.JobService;import com.fasterxml.jackson.databind.JsonNode;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController
public class McpServerController {
 private final JobService jobs;public McpServerController(JobService jobs){this.jobs=jobs;}
 @PostMapping("/mcp")public Map<String,Object> mcp(@RequestBody JsonNode request){String id=request.path("id").asText();String method=request.path("method").asText();try{Object result=switch(method){case"initialize"->Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of("listChanged",false)),"serverInfo",Map.of("name","career-nav-jobs","version","1.0.0"));case"tools/list"->Map.of("tools",List.of(Map.of("name","search_jobs","description","搜索招聘岗位、薪资与技术需求","inputSchema",Map.of("type","object","properties",Map.of("keyword",Map.of("type","string"),"city",Map.of("type","string"),"limit",Map.of("type","integer","default",20))))));case"tools/call"->call(request.path("params"));default->throw new IllegalArgumentException("Unsupported method: "+method);};return Map.of("jsonrpc","2.0","id",id,"result",result);}catch(Exception e){return Map.of("jsonrpc","2.0","id",id,"error",Map.of("code",-32602,"message",e.getMessage()));}}
 private Map<String,Object> call(JsonNode params){
  String name=params.path("name").asText();if(!"search_jobs".equals(name))throw new IllegalArgumentException("Unknown tool: "+name);
  JsonNode args=params.path("arguments");String keyword=args.path("keyword").asText("");String city=args.path("city").asText("");int limit=args.path("limit").asInt(20);
  List<JobView> result=jobs.list(keyword,city).stream().limit(limit).toList();
  List<Map<String,Object>> items=new java.util.ArrayList<>();
  for(JobView job:result){Map<String,Object> item=new java.util.LinkedHashMap<>();item.put("externalId",job.externalId());item.put("title",job.title());item.put("company",job.company());item.put("city",job.city());item.put("salary",job.salary());item.put("education",job.education());item.put("experience",job.experience());item.put("skills",job.skills());item.put("responsibilities",job.responsibilities());item.put("description",job.description());item.put("source",job.source());item.put("sourceUrl",job.sourceUrl());items.add(item);}
  Map<String,Object> payload=new java.util.LinkedHashMap<>();payload.put("jobs",items);payload.put("count",items.size());payload.put("generatedAt",java.time.LocalDateTime.now().toString());
  Map<String,Object> response=new java.util.LinkedHashMap<>();response.put("content",List.of(Map.of("type","text","text",writeJson(payload))));return response;
 }
 private String writeJson(Object value){try{return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(value);}catch(Exception e){return "{\"jobs\":[]}";}}
}
