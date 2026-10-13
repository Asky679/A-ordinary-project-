package com.careernav.service;
import com.careernav.domain.ApiModels.*;
import com.careernav.domain.JobImport;
import com.careernav.repository.JobRepository;
import com.careernav.repository.ResumeRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;
@Component
public class DataInitializer implements ApplicationRunner {
 private final ResumeRepository resumeRepository;private final JobRepository jobRepository;private final ResumeService resumeService;private final JobService jobService;
 public DataInitializer(ResumeRepository resumeRepository,JobRepository jobRepository,ResumeService resumeService,JobService jobService){this.resumeRepository=resumeRepository;this.jobRepository=jobRepository;this.resumeService=resumeService;this.jobService=jobService;}
 @Override public void run(ApplicationArguments args){if(resumeRepository.count()==0)resumeService.create(new ResumePayload("林同学","Java 后端开发工程师","杭州","13800000000","lin@example.com","本科","杭州电子科技大学","计算机科学与技术",2026,List.of("Java","Spring Boot","MySQL","Redis","Git","Docker"),List.of(new ProjectItem("校园二手交易平台","后端负责人","Spring Boot / MySQL / Redis","负责商品、订单和鉴权模块，使用 Redis 缓存热点商品。","接口平均响应时间从 620ms 降至 150ms，支撑 3000+ 注册用户"),new ProjectItem("智能学习助手","全栈开发","Spring Boot / Vue / LangChain4j","实现知识库检索、对话记忆和学习计划生成。","检索准确率提升 28%，生成一份计划耗时低于 5 秒")),List.of(),"计算机专业本科应届生，具备 Java Web 项目经验。善于定位问题，持续推进性能优化，希望成长为可靠的 Java 后端工程师。","Java 后端开发 / Spring Boot / MySQL / Redis / 项目性能优化"));if(jobRepository.count()==0){LocalDateTime n=LocalDateTime.now();jobService.importJobs(List.of(
new JobImport("seed-001","Java 后端开发工程师","云启科技","杭州","15-25K·14薪","本科","应届/1年",List.of("Java","Spring Boot","MySQL","Redis","Docker"),List.of("负责核心业务服务设计与开发","参与性能优化和线上问题排查"),"参与高并发电商核心系统研发，要求扎实的 Java 基础和工程意识。","MCP·示例招聘源","https://example.com/jobs/1",n.minusHours(1)),
new JobImport("seed-002","后端开发工程师（校招）","星河互联","上海","18-28K·15薪","本科","2026届",List.of("Java","Spring Cloud","MySQL","Redis","消息队列"),List.of("微服务模块开发","参与稳定性治理"),"面向 2026 届毕业生，重视计算机基础与项目实践。","MCP·示例招聘源","https://example.com/jobs/2",n.minusHours(2)),
new JobImport("seed-003","AI 应用开发工程师","智言科技","杭州","20-35K·14薪","本科","应届",List.of("Python","LangChain","RAG","MCP","Docker"),List.of("构建企业智能体应用","设计工具调用与知识库链路"),"负责 LLM 应用、Agent 编排与检索增强系统研发。","MCP·示例招聘源","https://example.com/jobs/3",n.minusHours(3)),
new JobImport("seed-004","前端开发工程师","极光数字","深圳","14-22K·14薪","本科","应届",List.of("HTML","CSS","JavaScript","Vue","TypeScript"),List.of("负责管理后台开发","推动组件库建设"),"重视前端基础、工程化能力与用户体验。","MCP·示例招聘源","https://example.com/jobs/4",n.minusHours(4)),
new JobImport("seed-005","Java 研发工程师","海岳数据","北京","16-26K·13薪","本科","应届",List.of("Java","Spring Boot","MyBatis","MySQL","Linux"),List.of("数据平台服务研发","编写高质量单元测试"),"数据中台方向，要求熟悉常用中间件与 Linux。","MCP·示例招聘源","https://example.com/jobs/5",n.minusHours(5)),
new JobImport("seed-006","大模型算法工程师","深蓝智能","北京","25-40K·15薪","硕士","应届",List.of("Python","PyTorch","Transformer","RAG","CUDA"),List.of("模型训练与评测","检索增强方案研究"),"参与大模型训练、微调与推理优化。","MCP·示例招聘源","https://example.com/jobs/6",n.minusHours(6)),
new JobImport("seed-007","测试开发工程师","云梯网络","成都","12-20K·13薪","本科","应届",List.of("Java","Python","Selenium","接口测试","CI/CD"),List.of("搭建自动化测试框架","参与质量平台建设"),"负责自动化测试、平台研发和质量效能提升。","MCP·示例招聘源","https://example.com/jobs/7",n.minusHours(7)),
new JobImport("seed-008","全栈开发工程师","新芽教育","南京","13-21K·13薪","本科","应届",List.of("Java","Spring Boot","Vue","MySQL","Docker"),List.of("前后端业务开发","参与产品快速迭代"),"教育科技方向，需要良好的产品意识和全栈能力。","MCP·示例招聘源","https://example.com/jobs/8",n.minusHours(8))
));}}
}
