export const jobSectors = {
  java: {
    name: 'Java 后端',
    planet: 'atlas-backend.webp',
    color: '#9acfff',
    summary: '用接口、数据库和测试搭建稳定的业务服务。',
    roles: {
      java: {
        name: 'Java 开发工程师', keyword: 'Java',
        duties: '实现业务接口和数据读写，排查服务故障，给关键流程补测试。',
        skills: ['Java', 'Spring Boot', 'SQL', '接口设计']
      },
      backend: {
        name: '后端开发工程师', keyword: '后端',
        duties: '设计服务之间的接口，处理缓存、异步任务和高并发场景下的数据一致性。',
        skills: ['HTTP', '关系型数据库', 'Redis', '自动化测试']
      }
    }
  },
  frontend: {
    name: '前端开发',
    planet: 'atlas-frontend.webp',
    color: '#ffbe97',
    summary: '把需求变成顺畅、可访问的网页和应用界面。',
    roles: {
      web: {
        name: 'Web 前端工程师', keyword: '前端',
        duties: '实现网页界面和交互，处理请求、加载和错误状态，优化页面性能。',
        skills: ['HTML / CSS', 'TypeScript', 'Vue 或 React', '浏览器调试']
      },
      mini: {
        name: '小程序开发工程师', keyword: '小程序',
        duties: '实现小程序页面和业务流程，接入平台接口并完成真机调试与发布。',
        skills: ['JavaScript', '小程序组件', '平台 API', '真机调试']
      }
    }
  },
  data: {
    name: '数据分析',
    planet: 'atlas-data.webp',
    color: '#a0e3d0',
    summary: '通过指标、查询和可视化回答实际的业务问题。',
    roles: {
      analyst: {
        name: '数据分析师', keyword: '数据分析',
        duties: '定义业务指标，清洗并分析数据，说明指标变化的原因和可验证的下一步。',
        skills: ['SQL', '数据清洗', '统计基础', '数据可视化']
      },
      bi: {
        name: 'BI 工程师', keyword: 'BI',
        duties: '维护指标口径与报表数据模型，搭建仪表板并核对报表数据。',
        skills: ['SQL', '数据建模', 'Power BI 或 Tableau', '指标管理']
      }
    }
  },
  ai: {
    name: 'AI 与大模型',
    planet: 'atlas-ai.webp',
    color: '#c8bbff',
    summary: '把模型接入产品，验证回答质量与真实使用效果。',
    roles: {
      llm: {
        name: '大模型应用工程师', keyword: '大模型',
        duties: '把模型能力接入产品，搭建文档检索或工具调用流程，评估质量与延迟。',
        skills: ['Python', '模型 API', 'RAG', '效果评估']
      },
      ml: {
        name: '机器学习工程师', keyword: '机器学习',
        duties: '整理训练数据，建立基线模型，评估并监控模型上线后的表现。',
        skills: ['Python', '机器学习', '模型评估', '数据处理']
      }
    }
  }
};

export function getJobSector(id) {
  return Object.hasOwn(jobSectors,id) ? jobSectors[id] : null;
}
