package com.careernav.service;
import com.careernav.domain.JobImport;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class JobRefreshService {
 private final McpJobGateway gateway;private final JobService jobService;
 public JobRefreshService(McpJobGateway gateway,JobService jobService){this.gateway=gateway;this.jobService=jobService;}
 @Scheduled(initialDelay=30000,fixedDelayString="${app.mcp.refresh-seconds:900}000")
 public void refresh(){if(!gateway.enabled())return;List<JobImport> jobs=gateway.fetchJobs("","",50);if(!jobs.isEmpty())jobService.importJobs(jobs);}
}
