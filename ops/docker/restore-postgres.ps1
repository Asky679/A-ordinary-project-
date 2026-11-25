param(
    [Parameter(Mandatory = $true)]
    [string]$Backup
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$backupPath = (Resolve-Path -LiteralPath $Backup).Path
$remote = "/tmp/careernav-restore.dump"
Set-Location $root

if ($env:FORCE -ne "1") {
    $answer = Read-Host "This replaces current business data. Type RESTORE to continue"
    if ($answer -ne "RESTORE") {
        Write-Host "Cancelled."
        exit 1
    }
}

docker compose stop app interview-agent | Out-Null
docker compose cp $backupPath "postgres:$remote"
if ($LASTEXITCODE -ne 0) { throw "Could not copy the backup into PostgreSQL" }

try {
    docker compose exec -T postgres sh -lc 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-acl "$1"' sh $remote
    if ($LASTEXITCODE -ne 0) { throw "pg_restore failed" }
    docker compose up -d app interview-agent
    Write-Host "PostgreSQL restore complete; application services restarted."
} finally {
    docker compose exec -T postgres rm -f $remote 2>$null | Out-Null
}
