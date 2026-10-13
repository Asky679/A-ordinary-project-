package com.careernav.controller;

import com.careernav.common.ApiResponse;
import com.careernav.domain.ApiModels.MarketSnapshotView;
import com.careernav.entity.JobCollectionRunEntity;
import com.careernav.entity.JobSourcePolicyEntity;
import com.careernav.repository.JobCollectionRunRepository;
import com.careernav.service.JobCollectionService;
import com.careernav.service.JobMarketAnalyticsService;
import com.careernav.service.SourcePolicyService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/collection")
public class CollectionAdminController {
    private final SourcePolicyService policyService;
    private final JobCollectionService collectionService;
    private final JobCollectionRunRepository runRepository;
    private final JobMarketAnalyticsService analyticsService;

    public CollectionAdminController(SourcePolicyService policyService, JobCollectionService collectionService,
                                     JobCollectionRunRepository runRepository,
                                     JobMarketAnalyticsService analyticsService) {
        this.policyService = policyService;
        this.collectionService = collectionService;
        this.runRepository = runRepository;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/policies")
    public ApiResponse<List<JobSourcePolicyEntity>> policies() { return ApiResponse.ok(policyService.list()); }

    @PutMapping("/policies/{sourceCode}/enabled")
    public ApiResponse<JobSourcePolicyEntity> setEnabled(@PathVariable String sourceCode, @RequestParam boolean enabled) {
        return ApiResponse.ok("来源状态已更新", policyService.setEnabled(sourceCode, enabled));
    }

    @PutMapping("/policies/{sourceCode}/feed")
    public ApiResponse<JobSourcePolicyEntity> configureFeed(@PathVariable String sourceCode,
                                                             @RequestParam String feedUrl,
                                                             @RequestParam(defaultValue = "5000") long intervalMs) {
        return ApiResponse.ok("授权 Feed 已配置", policyService.configureFeed(sourceCode, feedUrl, intervalMs));
    }

    @GetMapping("/runs")
    public ApiResponse<List<JobCollectionRunEntity>> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(runRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, Math.min(100, Math.max(1, limit)))));
    }

    @PostMapping("/run")
    public ApiResponse<JobCollectionRunEntity> run(@RequestParam String type) {
        return ApiResponse.ok("采集任务已执行", collectionService.runNow(type));
    }

    @GetMapping("/market/latest")
    public ApiResponse<MarketSnapshotView> latestMarket() { return ApiResponse.ok(analyticsService.latest()); }

    @PostMapping("/market/capture")
    public ApiResponse<MarketSnapshotView> captureMarket() {
        return ApiResponse.ok("月度市场快照已生成", analyticsService.captureMonthly());
    }
}
