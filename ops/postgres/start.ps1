$ErrorActionPreference = "Stop"
$env:LANG = $null
$env:LC_ALL = $null
$env:LC_CTYPE = $null
$pgHome = "D:\pgsql18\pgsql"
$pgData = "D:\何处是家乡\data\postgresql\pgdata"
$log = "D:\何处是家乡\data\postgresql\postgres.log"
$bin = Join-Path $pgHome "bin"
if (-not (Test-Path (Join-Path $pgData "PG_VERSION"))) {
    throw "PostgreSQL data directory is not initialized: $pgData"
}
$status = & (Join-Path $bin "pg_ctl.exe") -D $pgData status 2>$null
if ($LASTEXITCODE -ne 0) {
    & (Join-Path $bin "pg_ctl.exe") -D $pgData -l $log -o '"-p 5432"' start
    if ($LASTEXITCODE -ne 0) { throw "Failed to start PostgreSQL" }
}
& (Join-Path $bin "psql.exe") -h 127.0.0.1 -p 5432 -U careernav -d careernav -c "SELECT version(); SELECT count(*) AS jobs FROM jobs;"
