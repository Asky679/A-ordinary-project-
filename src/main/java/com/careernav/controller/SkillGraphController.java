package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.SkillGraph;import com.careernav.service.SkillGraphService;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/skills")
public class SkillGraphController {private final SkillGraphService service;public SkillGraphController(SkillGraphService service){this.service=service;}@GetMapping("/graph")public ApiResponse<SkillGraph> graph(@RequestParam(defaultValue="Java 后端开发")String role,@RequestParam(required=false)Long resumeId){return ApiResponse.ok(service.graph(role,resumeId));}}
