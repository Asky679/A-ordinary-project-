$agent = Split-Path -Parent $MyInvocation.MyCommand.Path
$python = Join-Path $agent ".venv\Scripts\python.exe"
$envFile = Join-Path $agent ".env"
if (-not (Test-Path -LiteralPath $python)) {
    throw "Python venv not found. Create it under the interview-agent directory and install requirements.txt"
}
if (Test-Path -LiteralPath $envFile) {
    Get-Content -Encoding UTF8 -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), "Process")
        }
    }
}
Set-Location $agent
& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
