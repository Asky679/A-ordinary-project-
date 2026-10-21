#!/usr/bin/env python3
"""Turn the mirrored interview repositories into one unified question bank.

Input :  D:\\何处是家乡\\data\\github-interview-datasets\\sources\\<repo>\\...
Output:  D:\\何处是家乡\\data\\github-interview-datasets\\interview_questions.jsonl
         D:\\何处是家乡\\data\\github-interview-datasets\\interview_questions.stats.json

Every record keeps its upstream source URL and license so the retrieval layer can
always attribute the original author. Nothing is redistributed from here.
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from datetime import date
from pathlib import Path
from urllib.parse import quote

ROOT = Path(r"D:\何处是家乡\data\github-interview-datasets")
SRC = ROOT / "sources"
OUT_JSONL = ROOT / "interview_questions.jsonl"
OUT_STATS = ROOT / "interview_questions.stats.json"
COLLECTED = date.today().isoformat()

REPO_URL = {
    "tech-interview-handbook": "https://github.com/yangshun/tech-interview-handbook/blob/main/",
    "JavaGuide": "https://github.com/Snailclimb/JavaGuide/blob/main/",
    "advanced-java": "https://github.com/doocs/advanced-java/blob/main/",
    "codeprep-questions": "https://github.com/codeprepdev/codeprep-questions/blob/main/",
}
REPO_LICENSE = {
    "tech-interview-handbook": "MIT",
    "JavaGuide": "Apache-2.0",
    "advanced-java": "CC-BY-SA-4.0",
    "codeprep-questions": "CC-BY-4.0",
}

HTML_BLOCK = re.compile(r"<(head|script|style)[\s\S]*?</\1>", re.I)
IMG = re.compile(r"!\[[^\]]*\]\([^)]*\)")
HTML_TAG = re.compile(r"</?[a-zA-Z][^>]*>")
MD_LINK = re.compile(r"\[([^\]]*)\]\(([^)]*)\)")
FRONTMATTER = re.compile(r"\A---\s*\n[\s\S]*?\n---\s*\n")

SKILLS = [
    (r"redis", "Redis"),
    (r"mysql", "MySQL"),
    (r"postgresql|postgres", "PostgreSQL"),
    (r"mongodb|mongo", "MongoDB"),
    (r"elasticsearch|elastic search", "Elasticsearch"),
    (r"kafka", "Kafka"),
    (r"rabbitmq", "RabbitMQ"),
    (r"rocketmq", "RocketMQ"),
    (r"消息队列|\bmq\b", "消息队列"),
    (r"dubbo", "Dubbo"),
    (r"zookeeper", "Zookeeper"),
    (r"nacos|eureka", "服务注册与发现"),
    (r"spring\s*cloud", "Spring Cloud"),
    (r"spring\s*boot", "Spring Boot"),
    (r"spring", "Spring"),
    (r"mybatis", "MyBatis"),
    (r"netty", "Netty"),
    (r"tomcat", "Tomcat"),
    (r"jvm|垃圾回收|gc 算法|类加载", "JVM"),
    (r"线程池|并发编程|volatile|synchronized|多线程", "Java 并发"),
    (r"\bjava\b", "Java"),
    (r"golang|go 语言", "Go"),
    (r"\bpython\b", "Python"),
    (r"javascript", "JavaScript"),
    (r"typescript", "TypeScript"),
    (r"\breact\b", "React"),
    (r"\bvue\b", "Vue"),
    (r"node\.?js", "Node.js"),
    (r"\bcss\b", "CSS"),
    (r"\bhtml\b", "HTML"),
    (r"docker|容器化", "Docker"),
    (r"kubernetes|\bk8s\b", "Kubernetes"),
    (r"\blinux\b", "Linux"),
    (r"\bgit\b", "Git"),
    (r"nginx", "Nginx"),
    (r"tcp|\bhttp\b|udp|计算机网络|三次握手|四次挥手|\bosi\b", "计算机网络"),
    (r"操作系统|进程与线程|死锁|内存管理|文件系统", "操作系统"),
    (r"算法|数据结构|排序|动态规划|链表|二叉树|红黑树|哈希|栈|队列|时间复杂", "算法与数据结构"),
    (r"设计模式|单例|工厂模式|观察者模式", "设计模式"),
    (r"微服务", "微服务"),
    (r"分布式", "分布式系统"),
    (r"高并发", "高并发"),
    (r"高可用|容灾|故障转移", "高可用"),
    (r"数据库|事务|索引|\bsql\b|分库分表", "数据库"),
    (r"缓存", "缓存"),
    (r"限流|熔断|降级|服务治理", "服务治理"),
    (r"幂等", "幂等设计"),
    (r"\bxss\b|\bcsrf\b|sql 注入|越权|加解密|\bhttps\b|\bjwt\b", "安全"),
    (r"大模型|大语言模型|\bllm\b|transformer|微调", "大语言模型"),
    (r"\brag\b|检索增强", "RAG"),
    (r"\bagent\b|智能体", "Agent"),
    (r"\bmcp\b", "MCP"),
    (r"prompt", "Prompt 工程"),
    (r"embedding|向量数据库|向量检索", "向量检索"),
    (r"单元测试|junit|测试用例|\btdd\b", "软件测试"),
    (r"jmeter|压测|性能测试", "性能测试"),
    (r"前端", "前端"),
    (r"性能优化|调优|\bqps\b", "性能优化"),
    (r"\bci/cd\b|持续集成|jenkins", "CI/CD"),
]

SKILL_PATTERNS = [(re.compile(pattern, re.I), label) for pattern, label in SKILLS]

STAGE_BY_PATH = [
    ("behavioral", "behavior"), ("behavior", "behavior"), ("self-introduction", "self_intro"),
    ("negotiation", "offer"), ("resume", "self_intro"), ("system-design", "system"),
    ("algorithms", "algorithm"), ("data-structure", "algorithm"), ("cs-basics/algorithms", "algorithm"),
    ("high-concurrency", "backend"), ("high-availability", "system"),
    ("distributed-system", "system"), ("micro-service", "system"),
    ("ai/interview-questions", "ai"), ("ai/agent", "ai"), ("ai/rag", "ai"), ("ai/llm-basis", "ai"),
    ("coding-interview", "algorithm"), ("final-questions", "reverse"), ("mock-interviews", "process"),
    ("interview-formats", "process"), ("engineering-levels", "process"), ("choosing-between-companies", "process"),
    ("understanding-compensation", "offer"), ("landscape", "process"), ("career-growth", "process"),
    ("best-practice-questions", "algorithm"), ("interviewer-cheatsheet", "process"),
]

ROLE_BY_PATH = [
    ("docs/ai/", "AI 应用开发"), ("cs-basics", "计算机基础"), ("advanced-java", "Java 后端"),
    ("docs/java/", "Java 后端"), ("docs/database/", "后端 / 数据库"), ("docs/distributed-system/", "后端 / 分布式"),
    ("docs/system-design/", "后端 / 架构"), ("docs/devops/", "运维 / DevOps"),
    ("docs/tools/", "工程效率"), ("behavioral", "通用 / 行为面试"),
]

GENERIC_HEADINGS = {
    "目录", "前言", "总结", "小结", "参考资料", "参考", "相关文章", "公众号", "说明", "简介",
    "写在最后", "写在前面", "本文", "序言", "背景", "概述", "引言", "更新记录", "变更记录",
    "introduction", "summary", "resources", "learning resources", "recommended courses",
    "recommended practice questions", "essential questions", "where to go from here",
    "references", "further reading", "conclusion", "overview", "notes",
}

QUESTION_MARKERS = (
    "为什么", "什么", "怎么", "如何", "哪些", "区别", "差异", "优势", "缺点", "原理", "机制",
    "作用", "是否", "能否", "简述", "说说", "对比", "选型", "理解", "介绍", "好处", "坑",
    "what", "why", "how", "when", "which", "difference", "vs", "compare", "explain",
)


def read_text(path: Path) -> str:
    for encoding in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return path.read_text(encoding=encoding)
        except Exception:  # noqa: BLE001
            continue
    return ""


def clean(text: str) -> str:
    text = HTML_BLOCK.sub("", text)
    text = IMG.sub("", text)
    text = HTML_TAG.sub("", text)
    text = MD_LINK.sub(lambda m: m.group(1), text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def h1(text: str) -> str:
    for line in text.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return ""


def detect_lang(text: str) -> str:
    if not text:
        return "zh"
    cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
    return "zh" if cjk / max(len(text), 1) >= 0.10 else "en"


def infer_skills(text: str) -> list:
    found = []
    for pattern, label in SKILL_PATTERNS:
        if label in found:
            continue
        if pattern.search(text):
            found.append(label)
    return found[:6]


def infer_by_table(path: str, table, default: str) -> str:
    lowered = path.lower()
    for needle, value in table:
        if needle in lowered:
            return value
    return default


def normalize_question(text: str) -> str:
    text = text.strip().lower()
    text = re.sub(r"^[\s\d.、()（）#*\-]+", "", text)
    text = re.sub(r"[\s，。！？、,.!?;；:：\"'`*_\[\]()【】]+", "", text)
    return text


def make_record(source, path, question, answer, *, kind, stage=None, role=None, extra=None) -> dict:
    question = re.sub(r"\s+", " ", clean(question)).strip()
    answer = clean(answer)
    blob = path + " " + question + " " + answer[:400]
    record = {
        "id": source + ":" + re.sub(r"[^a-zA-Z0-9\u4e00-\u9fff]+", "-", path)[-70:] + ":" + str(abs(hash(normalize_question(question))) % 100000),
        "question": question,
        "answer": answer,
        "kind": kind,
        "role": role or infer_by_table(path, ROLE_BY_PATH, "软件工程师"),
        "stage": stage or infer_by_table(path, STAGE_BY_PATH, "backend"),
        "skills": infer_skills(blob),
        "difficulty": "标准",
        "lang": detect_lang(question + answer),
        "source": source,
        "sourcePath": path,
        "sourceUrl": REPO_URL[source] + quote(path, safe="/"),
        "license": REPO_LICENSE[source],
        "collectedAt": COLLECTED,
    }
    if extra:
        record.update(extra)
    return record


def split_sections(text: str, levels=(2, 3)) -> list:
    """Return [(heading, level, body)] for the given ATX heading levels."""
    pattern = re.compile(r"^(#{1,6})\s+(.*)$", re.M)
    marks = [(m.start(), len(m.group(1)), m.group(2).strip(), m.end()) for m in pattern.finditer(text)]
    sections = []
    for index, (start, level, heading, end) in enumerate(marks):
        if level not in levels:
            continue
        stop = len(text)
        for next_start, next_level, _h, _e in marks[index + 1:]:
            if next_level <= level:
                stop = next_start
                break
        sections.append((heading, level, text[end:stop].strip()))
    return sections


# --------------------------------------------------------------------------- #
# adapters
# --------------------------------------------------------------------------- #
def parse_advanced_java(root: Path) -> list:
    records = []
    docs = root / "docs"
    if not docs.exists():
        return records
    for path in sorted(docs.rglob("*.md")):
        rel = path.relative_to(root).as_posix()
        if path.name.lower() == "readme.md" or rel.endswith("docs/index.md") or "/extra-page/" in "/" + rel:
            continue
        text = clean(FRONTMATTER.sub("", read_text(path)))
        if not text:
            continue
        title = h1(text) or path.stem
        sections = split_sections(text, levels=(2,))
        question = ""
        answers = []
        for heading, _level, body in sections:
            if "面试题" in heading and "剖析" not in heading:
                if not question:
                    question = body or heading
            elif body:
                answers.append(body)
        if not question:
            question = title
        if not answers:
            body = re.sub(r"^#\s+.*$", "", text, count=1, flags=re.M).strip()
            answers = [body]
        answer = "\n\n".join(answers)
        if len(question) < 4 or len(answer) < 60:
            continue
        records.append(make_record("advanced-java", rel, question, answer, kind="qa"))
    return records


def parse_java_guide(root: Path) -> list:
    records = []
    docs = root / "docs"
    if not docs.exists():
        return records
    excluded = ("about-the-author/", "books/", "ai-coding/", "zhuanlan/", "roadmap/", "sponsor/")
    for path in sorted(docs.rglob("*.md")):
        rel = path.relative_to(root).as_posix()
        tail = rel[len("docs/"):]
        if path.name.lower() == "readme.md" or any(tail.startswith(item) for item in excluded):
            continue
        text = clean(FRONTMATTER.sub("", read_text(path)))
        if not text:
            continue
        is_interview_file = "interview-questions" in rel
        for heading, _level, body in split_sections(text, levels=(2, 3)):
            if not body or len(body) < 120:
                continue
            heading_clean = re.sub(r"^[\d.]+\s*", "", heading).strip()
            if not heading_clean or heading_clean.lower() in GENERIC_HEADINGS:
                continue
            looks_like_question = any(marker in heading_clean.lower() for marker in QUESTION_MARKERS) or "?" in heading or "？" in heading
            if not (looks_like_question or is_interview_file):
                continue
            if len(heading_clean) < 4 or len(heading_clean) > 120:
                continue
            records.append(make_record("JavaGuide", rel + "#" + heading_clean, heading_clean, body, kind="qa"))
    return records


def parse_handbook(root: Path) -> list:
    records = []
    base = root / "apps" / "website" / "contents"
    if not base.exists():
        return records
    for path in sorted(base.rglob("*.md")):
        rel = path.relative_to(root).as_posix()
        text = clean(FRONTMATTER.sub("", read_text(path)))
        if not text:
            continue
        title = h1(text) or path.stem
        if path.stem in ("behavioral-interview-questions", "best-practice-questions", "final-questions"):
            stage = "behavior" if "behavioral" in path.stem else "reverse" if "final" in path.stem else "algorithm"
            for line in text.splitlines():
                item = line.strip()
                if not item.startswith(("- ", "* ")):
                    continue
                item = item[2:].strip()
                item = re.sub(r"\*\*", "", item)
                if len(item) < 15 or len(item) > 300:
                    continue
                if item.endswith(":") and len(item) < 40:
                    continue
                records.append(make_record("tech-interview-handbook", rel, item, "", kind="question", stage=stage))
            continue
        for heading, _level, body in split_sections(text, levels=(2,)):
            if len(body) < 220:
                continue
            heading_clean = heading.strip()
            if heading_clean.lower() in GENERIC_HEADINGS:
                continue
            records.append(make_record("tech-interview-handbook", rel + "#" + heading_clean,
                                       title + " - " + heading_clean, body, kind="note"))
    return records


def codeprep_answer(item: dict) -> str:
    parts = []
    options = item.get("options")
    if isinstance(options, list) and options:
        parts.append("选项：" + " / ".join(str(opt) for opt in options))
    answer = item.get("answer")
    if isinstance(answer, str) and answer.strip():
        parts.append(answer.strip())
    elif isinstance(answer, dict):
        code = answer.get("code")
        if code:
            parts.append("```" + str(answer.get("language", "")) + "\n" + str(code) + "\n```")
        for key in ("correct", "value", "text", "answer", "selected", "choices"):
            value = answer.get(key)
            if value:
                parts.append(str(value) if not isinstance(value, list) else " / ".join(str(v) for v in value))
        leftovers = {k: v for k, v in answer.items() if k not in ("code", "language", "correct", "value", "text", "answer", "selected", "choices", "buggyLines")}
        if leftovers and not parts:
            parts.append(json.dumps(leftovers, ensure_ascii=False))
    elif isinstance(answer, list):
        parts.append(" / ".join(str(v) for v in answer))
    if item.get("explanation"):
        parts.append("解析：" + str(item["explanation"]))
    return "\n\n".join(part for part in parts if part)


DIFFICULTY_MAP = {"easy": "基础", "medium": "标准", "hard": "进阶"}


def parse_codeprep(root: Path) -> list:
    records = []
    target = root / "data" / "questions.json"
    if not target.exists():
        return records
    try:
        payload = json.loads(read_text(target))
    except Exception:  # noqa: BLE001
        return records
    for item in payload.get("questions", []):
        question = str(item.get("prompt", "")).strip()
        answer = codeprep_answer(item)
        if len(question) < 8 or len(answer) < 20:
            continue
        tags = [str(tag) for tag in item.get("tags", [])]
        tech = str(item.get("technologyName") or item.get("technology") or "")
        record = make_record(
            "codeprep-questions",
            "data/questions.json#" + str(item.get("id", "")),
            question,
            answer,
            kind="qa",
            role="软件工程师",
            stage="algorithm" if tech in ("JavaScript", "TypeScript", "Python", "CSS", "Web Platform") else "backend",
            extra={
                "difficulty": DIFFICULTY_MAP.get(str(item.get("difficultyLabel", "")).lower(), "标准"),
                "questionType": item.get("type"),
                "tags": tags,
                "skills": [tech] + tags if tech else tags,
            },
        )
        records.append(record)
    return records


ADAPTERS = [
    ("advanced-java", parse_advanced_java),
    ("JavaGuide", parse_java_guide),
    ("tech-interview-handbook", parse_handbook),
    ("codeprep-questions", parse_codeprep),
]


def main() -> int:
    all_records = []
    per_source = {}
    for name, adapter in ADAPTERS:
        root = SRC / name
        if not root.exists():
            print("missing source dir: " + str(root), flush=True)
            continue
        parsed = adapter(root)
        per_source[name] = len(parsed)
        print("{0}: {1} records".format(name, len(parsed)), flush=True)
        all_records.extend(parsed)

    deduped = []
    seen = set()
    duplicates = 0
    for record in all_records:
        key = normalize_question(record["question"])
        if not key or key in seen:
            duplicates += 1
            continue
        seen.add(key)
        deduped.append(record)

    deduped.sort(key=lambda item: (item["source"], item["sourcePath"]))
    with OUT_JSONL.open("w", encoding="utf-8", newline="\n") as handle:
        for record in deduped:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    stats = {
        "generatedAt": COLLECTED,
        "outputJsonl": str(OUT_JSONL),
        "perSourceRaw": per_source,
        "totalRaw": len(all_records),
        "totalDeduped": len(deduped),
        "droppedAsDuplicate": duplicates - sum(1 for r in all_records if not normalize_question(r["question"])),
        "bySource": dict(Counter(r["source"] for r in deduped)),
        "byKind": dict(Counter(r["kind"] for r in deduped)),
        "byStage": dict(Counter(r["stage"] for r in deduped)),
        "byLang": dict(Counter(r["lang"] for r in deduped)),
        "byRole": dict(Counter(r["role"] for r in deduped)),
        "topSkills": dict(Counter(skill for r in deduped for skill in r["skills"]).most_common(25)),
        "licenses": REPO_LICENSE,
        "attribution": "Every record stores sourceUrl + license; content is used for local retrieval with attribution to the original author.",
    }
    OUT_STATS.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
