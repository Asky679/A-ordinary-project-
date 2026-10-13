# CareerNav LangGraph Interview Agent

Java Spring Boot 负责业务数据和面试会话，Python LangGraph 负责自主规划、工具选择、追问和评分。

## 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

或者：

```powershell
powershell -ExecutionPolicy Bypass -File .\start.ps1
```

## 配置在线模型

```powershell
$env:OPENAI_API_KEY="your-key"
$env:OPENAI_BASE_URL="https://api.deepseek.com/v1"
$env:OPENAI_MODEL="deepseek-chat"
```

没有 API Key 时使用本地 LangGraph 规划器回退，仍会调用工具完成评分和追问。