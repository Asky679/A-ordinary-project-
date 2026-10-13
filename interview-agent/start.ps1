$root = "D:\何处是家乡\new pj"
$agent = Join-Path $root "interview-agent"
$python = Join-Path $agent ".venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    throw "Python venv not found. Run: python -m venv $agent\.venv and install requirements.txt"
}
Set-Location $agent
& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8001