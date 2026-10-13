package com.careernav.service;
import com.careernav.config.AppProperties;
import com.careernav.domain.ApiModels.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
public class DashboardService {
 private final ResumeService resumeService;private final JobService jobService;private final InterviewService interviewService;private final DiagnosisService diagnosisService;private final AppProperties properties;
 public DashboardService(ResumeService resumeService,JobService jobService,InterviewService interviewService,DiagnosisService diagnosisService,AppProperties properties){this.resumeService=resumeService;this.jobService=jobService;this.interviewService=interviewService;this.diagnosisService=diagnosisService;this.properties=properties;}
 @Transactional(readOnly=true) public DashboardView dashboard(){List<ResumeView> resumes=resumeService.list();ResumeView primary=resumes.isEmpty()?null:resumes.getFirst();List<JobRecommendation> recommendations=primary==null?List.of():jobService.recommend(primary,null,5);int avgMatch=recommendations.isEmpty()?0:(int)Math.round(recommendations.stream().mapToInt(JobRecommendation::matchScore).average().orElse(0));DashboardStats stats=new DashboardStats(resumeService.list().size(),jobService.count(),interviewService.count(),diagnosisService.count(),diagnosisService.averageScore(),avgMatch);String mode=properties.getAgent().getApiKey()!=null&&!properties.getAgent().getApiKey().isBlank()?"LangChain4j 在线模型":"LangChain4j 本地推理";return new DashboardView(stats,List.of("编写简历","岗位推荐","模拟面试","简历诊断"),recommendations,primary==null?null:diagnosisService.latest(primary.id()),mode,properties.getMcp().isEnabled());}
}
