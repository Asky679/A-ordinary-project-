from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "interview-experiences" / "source"
OUTPUT = ROOT / "data" / "interview-experiences" / "patterns.jsonl"

STAGE_RULES = [
    ("故障与边界", ["异常", "故障", "超时", "失效", "延迟", "并发", "一致性", "边界", "最差", "失败", "限流", "降级", "宕机", "假死", "锁超时"]),
    ("结果验证", ["验证", "评测", "指标", "量化", "测试", "证明", "效果", "数据", "怎么知道", "上线后"]),
    ("方案取舍", ["为什么", "区别", "选型", "优缺点", "替代", "对比", "取舍", "如何设计", "怎么设计", "选择", "方案"]),
    ("项目实现", ["项目", "负责", "实现", "数据库表", "代码", "接口", "工作流", "节点", "架构", "链路", "功能"]),
    ("基础概念", ["什么是", "说说", "介绍一下", "原理", "了解", "参数", "流程", "作用", "机制"]),
]

TAG_WORDS = [
    "Java", "JVM", "GC", "Spring", "Spring Boot", "Spring MVC", "Spring Cloud", "AOP", "IOC", "事务", "MyBatis",
    "MySQL", "SQL", "索引", "Redis", "Redisson", "分布式锁", "线程池", "并发", "锁", "synchronized", "ReentrantLock",
    "MQ", "RabbitMQ", "RocketMQ", "Kafka", "Elasticsearch", "DolphinDB", "Docker", "Kubernetes", "Linux",
    "项目", "工作流", "数据库", "接口", "微服务", "分布式", "高并发", "性能优化", "缓存", "RAG", "Agent", "MCP",
    "Tool Calling", "上下文", "记忆", "向量检索", "混合检索", "RRF", "LangChain", "LangGraph", "评测", "算法",
    "手撕", "实习", "测试", "自动化测试", "稳定性", "消息队列", "支付", "智慧农业", "MES", "CDN",
    "流程", "节点", "连线", "审批", "会签", "或签", "流程实例", "待办", "定时任务", "分库分表",
    "索引", "隔离级别", "事务传播", "类加载", "内存模型", "垃圾回收", "接口幂等", "幂等",
]

OVERRIDES = {
    "快手Java一面实录_双人对话稿.md": {
        "role": "Java/测试开发", "companyType": "大厂", "level": "校招/实习"
    },
    "深圳小厂Java面试_双人对话稿.md": {
        "role": "Java 后端开发", "companyType": "深圳小厂", "level": "社招"
    },
    "暑期实习Java后端小厂面试实录.txt": {
        "role": "Java 后端开发", "companyType": "小厂", "level": "暑期实习"
    },
    "Java实习生真实面试对话记录.txt": {
        "role": "Java 后端开发", "companyType": "中小厂", "level": "实习"
    },
    "启明信息面试_刚培训完包装被看穿.txt": {
        "role": "Java 后端开发", "companyType": "中厂/国企项目", "level": "包装三年经验"
    },
    "土木工程转行Java_三年经验智慧农业.txt": {
        "role": "Java 后端开发", "companyType": "行业软件公司", "level": "三年经验/转行"
    },
    "一年经验Java开发_MES制造系统背景.txt": {
        "role": "Java 后端开发", "companyType": "制造业信息化", "level": "一年经验"
    },
    "实战面试_面试去实习的.txt": {
        "role": "Java/数据方向实习", "companyType": "中小厂/实习面试", "level": "实习", "outcome": "一面通过/进入下一轮"
    },
    "实战面试_自己开发的项目能讲解溜吗.txt": {
        "role": "Java 后端开发", "companyType": "中小厂/实战面试", "level": "社招", "outcome": ""
    },
    "大厂我来了_三年经验基金业务.txt": {
        "role": "Java 后端开发", "companyType": "大厂", "level": "三年经验", "outcome": ""
    },
    "实战面试_2年经验_面试官很中意.txt": {
        "role": "Java 后端开发", "companyType": "中小厂", "level": "两年经验", "outcome": "面试官中意/未给正式结论"
    },
    "5年Java高级开发_字节社招一面.txt": {
        "role": "Java 后端开发", "companyType": "字节/大厂", "level": "五年经验", "outcome": ""
    },
    "Java实战面试_一面通过_八股快筛.txt": {
        "role": "Java 后端开发", "companyType": "中小厂", "level": "校招/社招", "outcome": "一面通过"
    },
    "初面_感觉有点紧张啊.txt": {
        "role": "Java 后端开发", "companyType": "中小厂/初面", "level": "校招/社招", "outcome": ""
    },
    "三年经验Java开发_没学过这课不行.txt": {
        "role": "Java 后端开发", "companyType": "社招/中小厂", "level": "三年经验", "outcome": ""
    },
    "典型 Java 实战面试.txt": {
        "role": "Java 后端开发", "companyType": "中小厂", "level": "校招/社招", "outcome": ""
    },
    "阿里外包驻场 Java.txt": {
        "role": "Java 后端开发", "companyType": "阿里外包/驻场", "level": "社招", "outcome": ""
    },
    "经纬恒润 Java 开发.txt": {
        "role": "Java 后端开发", "companyType": "经纬恒润/中厂", "level": "校招/社招", "outcome": ""
    },
    "携程 Java 开发.txt": {
        "role": "Java 后端开发", "companyType": "携程/大厂", "level": "25届硕士/校招", "outcome": ""
    },
    "用友 Java 开发.txt": {
        "role": "Java 后端开发", "companyType": "用友/企业软件", "level": "五年经验", "outcome": ""
    },
}


def stage_for(text: str) -> str:
    for stage, words in STAGE_RULES:
        if any(word in text for word in words):
            return stage
    return "基础概念"


def tags_for(text: str) -> list[str]:
    lower = text.lower()
    tags = [word for word in TAG_WORDS if word.lower() in lower]
    return list(dict.fromkeys(tags))[:12]


def clean_text(value: str, limit: int = 700) -> str:
    value = re.sub(r"\*+", "", value or "")
    value = re.sub(r"^\[[0-9:]+\]\s*", "", value)
    value = re.sub(r"\s+", " ", value).strip(" ：:，,。")
    return value[:limit]


def section_blocks(text: str) -> list[tuple[str, list[str]]]:
    sections: list[tuple[str, list[str]]] = []
    current_title = "面试对话"
    current_lines: list[str] = []
    for raw in text.splitlines():
        line = raw.rstrip()
        heading_md = re.match(r"^##+\s+(.+)$", line.strip())
        heading_txt = re.match(r"^【(.+)】\s*$", line.strip())
        heading_cn = re.match(r"^([一二三四五六七八九十百]+)[、.．]\s*(.+)$", line.strip())
        if heading_md or heading_txt or heading_cn:
            if current_lines:
                sections.append((current_title, current_lines))
            if heading_md or heading_txt:
                current_title = (heading_md or heading_txt).group(1).strip()
            else:
                current_title = heading_cn.group(2).strip()
            current_lines = []
        else:
            current_lines.append(line)
    if current_lines:
        sections.append((current_title, current_lines))
    return sections


QUESTION_CUES = (
    "?", "？", "什么", "为什么", "怎么", "如何", "是否", "有没有", "能不能", "有哪些",
    "说一下", "讲一下", "介绍一下", "解释", "描述", "举个", "说说", "了解", "区别", "过程", "设计",
)


def interviewer_blocks(lines: list[str]) -> list[str]:
    blocks: list[str] = []
    speaker = None
    buffer: list[str] = []

    def flush():
        nonlocal buffer, speaker
        if speaker == "interviewer":
            content = clean_text(" ".join(buffer))
            if len(content) >= 6 and any(cue in content for cue in QUESTION_CUES):
                blocks.append(content)
        buffer = []

    for raw in lines:
        line = raw.strip()
        if not line or set(line) <= {"-", "="}:
            continue
        speaker_match = re.search(r"(面试官[A-Za-z0-9一二三四五六七八九十]*|面试者|被面试者|候选人)\*{0,2}\s*[:：]\s*(.*)$", line)
        if speaker_match:
            flush()
            speaker = "interviewer" if speaker_match.group(1).startswith("面试官") else "candidate"
            inline = speaker_match.group(2).strip()
            if inline:
                buffer.append(inline)
            continue
        if speaker is not None:
            buffer.append(line)
    flush()
    return blocks


def metadata(path: Path, text: str) -> dict:
    title = path.stem.replace("_", " ")
    title_match = re.search(r"^#\s+(.+)$", text, re.MULTILINE)
    if title_match:
        title = title_match.group(1).strip()
    else:
        for line in text.splitlines()[:15]:
            match = re.search(r"面试实录\s*[:：]\s*(.+)", line)
            if match:
                title = match.group(1).strip()
                break
    url_match = re.search(r"(https://b23\.tv/\S+)", text)
    bv_match = re.search(r"(BV[0-9A-Za-z]+)", text)
    source_url = url_match.group(1) if url_match else (f"https://www.bilibili.com/video/{bv_match.group(1)}" if bv_match else "")
    outcome = ""
    result_match = re.search(r"结果\s*[:：]\s*(.+)", text)
    if result_match:
        outcome = clean_text(result_match.group(1), 120)
    elif "通过" in path.name:
        outcome = "通过"
    elif "被刷" in path.name or "露馅" in path.name:
        outcome = "未通过/被识破"
    override = OVERRIDES.get(path.name, {})
    outcome = override.get("outcome", outcome)
    level = override.get("level")
    if not level:
        for label in ("一年经验", "两年经验", "三年经验", "五年经验"):
            if label in path.name:
                level = label
                break
        if not level:
            year = re.search(r"(\d+)\s*年(?:经验|Java)", path.name)
            if year:
                level = year.group(1) + "年经验"
    return {
        "sourceTitle": title,
        "sourceUrl": source_url,
        "sourceFile": path.name,
        "role": override.get("role", "Java 后端开发"),
        "companyType": override.get("companyType", "社招/中小厂"),
        "level": level or "校招/社招",
        "outcome": outcome,
    }


def dialogue_patterns(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8", errors="replace")
    meta = metadata(path, text)
    source_id = "java-" + hashlib.sha1(path.name.encode("utf-8")).hexdigest()[:8]
    patterns = []
    for section_index, (heading, lines) in enumerate(section_blocks(text), start=1):
        questions = interviewer_blocks(lines)
        if not questions:
            continue
        full_text = heading + " " + " ".join(questions[:5])
        patterns.append({
            "id": f"{source_id}-{section_index:02d}",
            "sourceId": source_id,
            **meta,
            "section": clean_text(heading, 120),
            "stage": stage_for(full_text),
            "tags": tags_for(full_text),
            "question": questions[0],
            "followUps": questions[1:6],
            "interviewerMoves": [
                "从候选人上一段项目或技术回答继续追问",
                "要求说明个人职责、实现过程和方案依据",
                "用具体场景、最差情况或故障现象检验边界",
                "必要时提示方向，再观察候选人能否继续展开",
            ],
            "usageNote": "只学习提问方式和追问链，不复述候选人答案，不把真实公司信息当成官方题库。",
        })
    return patterns


def experience_patterns(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    patterns = []
    current_company = ""
    current_role = ""
    current_section = ""
    current_main = None
    counter = 0
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("## "):
            header = line[3:].strip()
            current_section = header
            parts = re.split(r"\s+-\s+", header, maxsplit=1)
            if len(parts) == 2:
                current_company = re.sub(r"^\d+\.\s*", "", parts[0]).strip()
                current_role = parts[1].strip()
            current_main = None
            continue
        if line.startswith("### "):
            current_section = line[4:].strip()
            current_main = None
            continue
        main = re.match(r"^\d+\.\s+(.+)$", line.strip())
        if main:
            counter += 1
            question = clean_text(main.group(1), 700)
            current_main = {
                "id": f"ai-agent-experience-{counter:03d}",
                "sourceId": "ai-agent-experience-collection",
                "sourceTitle": "面经合集01",
                "sourceFile": path.name,
                "sourceUrl": "",
                "role": current_role or "AI/Java 开发",
                "companyType": current_company or "互联网公司",
                "level": "校招/社招",
                "outcome": "",
                "section": current_section,
                "stage": stage_for(question),
                "tags": tags_for(current_section + " " + question),
                "question": question,
                "followUps": [],
                "interviewerMoves": ["围绕一个主题连续下钻", "从项目事实继续追问方案、边界和验证方式"],
                "usageNote": "公开面经中的真实问题，仅作为提问风格和主题来源。",
            }
            patterns.append(current_main)
            continue
        nested = re.match(r"^\s*-\s+(.+)$", line)
        if nested and current_main is not None:
            child = clean_text(nested.group(1), 500)
            if child and len(current_main["followUps"]) < 6:
                current_main["followUps"].append(child)
    return patterns

all_patterns = []
for path in sorted(SOURCE.rglob("*")):
    if not path.is_file():
        continue
    if path.suffix.lower() not in {".md", ".txt"}:
        continue
    if path.name == "ai-agent-experiences.md":
        all_patterns.extend(experience_patterns(path))
    elif path.name not in {"README.md", "sources.md"}:
        all_patterns.extend(dialogue_patterns(path))

seen = {}
for row in all_patterns:
    key = re.sub(r"\s+", "", row["question"].lower())
    if key not in seen or len(row.get("followUps", [])) > len(seen[key].get("followUps", [])):
        seen[key] = row
rows = list(seen.values())
OUTPUT.parent.mkdir(parents=True, exist_ok=True)
with OUTPUT.open("w", encoding="utf-8") as fh:
    for row in rows:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
print(f"wrote {len(rows)} patterns to {OUTPUT}")
