$ErrorActionPreference = "Stop"
$env:LANG = $null
$env:LC_ALL = $null
$env:LC_CTYPE = $null
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$pgHome = "D:\pgsql18\pgsql"
$pgData = Join-Path $root "data\postgresql\pgdata"
$log = Join-Path $root "target\postgres-runtime.log"
$err = Join-Path $root "target\postgres-runtime.err.log"
$bin = Join-Path $pgHome "bin"
if (-not (Test-Path (Join-Path $pgData "PG_VERSION"))) {
    throw "PostgreSQL data directory is not initialized: $pgData"
}
# If no server is running, a leftover lock file is stale and safe to remove.
$pidFile = Join-Path $pgData "postmaster.pid"
$ready = & (Join-Path $bin "pg_isready.exe") -h 127.0.0.1 -p 5432 2>$null
if ($LASTEXITCODE -ne 0 -and (Test-Path -LiteralPath $pidFile)) {
    $oldPid = (Get-Content -LiteralPath $pidFile -TotalCount 1) -as [int]
    if (-not $oldPid -or -not (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}
$ready = & (Join-Path $bin "pg_isready.exe") -h 127.0.0.1 -p 5432 2>$null
if ($LASTEXITCODE -ne 0) {
    $args = "-D `"$pgData`" -p 5432"
    Start-Process -FilePath (Join-Path $bin "postgres.exe") -ArgumentList $args -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError $err | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        & (Join-Path $bin "pg_isready.exe") -h 127.0.0.1 -p 5432 2>$null | Out-Null
    } while ($LASTEXITCODE -ne 0 -and (Get-Date) -lt $deadline)
}
& (Join-Path $bin "pg_isready.exe") -h 127.0.0.1 -p 5432
& (Join-Path $bin "psql.exe") -h 127.0.0.1 -p 5432 -U careernav -d careernav -c "SELECT current_database(); SELECT count(*) AS interview_sessions FROM interview_sessions;"
