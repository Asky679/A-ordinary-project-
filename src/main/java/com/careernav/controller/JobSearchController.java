package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.JobView;import com.careernav.service.JobVectorSearchService;
import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/jobs")
public class JobSearchController {
 private final JobVectorSearchService service;public JobSearchController(JobVectorSearchService service){this.service=service;}
 @GetMapping("/hybrid") public ApiResponse<List<JobView>> search(@RequestParam(defaultValue="")String query,@RequestParam(defaultValue="")String city,@RequestParam(required=false)Integer year,@RequestParam(defaultValue="20")int limit){return ApiResponse.ok(service.search(query,city,year,limit));}
 @GetMapping("/vector-status") public ApiResponse<Map<String,Object>> status(){return ApiResponse.ok(service.status());}
 @PostMapping("/vector/reindex") public ApiResponse<Map<String,Object>> reindex(@RequestParam(defaultValue="100")int limit){return ApiResponse.ok("向量索引任务已完成",Map.of("updated",service.reindex(limit)));}
}
