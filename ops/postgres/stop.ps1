$env:LANG = $null
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$bin = "D:\pgsql18\pgsql\bin"
$pgData = Join-Path $root "data\postgresql\pgdata"
& (Join-Path $bin "pg_ctl.exe") -D $pgData stop -m fast
