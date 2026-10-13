package com.careernav.controller;
import com.careernav.common.ApiResponse;import com.careernav.domain.ApiModels.DashboardView;import com.careernav.service.DashboardService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/dashboard")
public class DashboardController {private final DashboardService service;public DashboardController(DashboardService service){this.service=service;}@GetMapping public ApiResponse<DashboardView> get(){return ApiResponse.ok(service.dashboard());}}
