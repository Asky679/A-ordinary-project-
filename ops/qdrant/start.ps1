$root = "D:\何处是家乡\new pj"
Set-Location (Join-Path $root "tools\qdrant")
& ".\qdrant.exe" --disable-telemetry
