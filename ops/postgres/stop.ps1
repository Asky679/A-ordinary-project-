$env:LANG = $null
$bin = "D:\pgsql18\pgsql\bin"
$pgData = "D:\何处是家乡\data\postgresql\pgdata"
& (Join-Path $bin "pg_ctl.exe") -D $pgData stop -m fast
