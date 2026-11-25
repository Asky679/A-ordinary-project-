$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root
New-Item -ItemType Directory -Force -Path "backups" | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$remote = "/tmp/careernav-$stamp.dump"
$target = Join-Path $root "backups\postgres-$stamp.dump"
try {
    docker compose exec -T postgres sh -lc "pg_dump -U `$POSTGRES_USER -d `$POSTGRES_DB -Fc -f $remote"
    if ($LASTEXITCODE -ne 0) { throw "pg_dump failed" }
    docker compose cp "postgres:$remote" $target
    if ($LASTEXITCODE -ne 0) { throw "docker compose cp failed" }
    Write-Host "Backup complete: $target"
} finally {
    docker compose exec -T postgres rm -f $remote 2>$null | Out-Null
}
