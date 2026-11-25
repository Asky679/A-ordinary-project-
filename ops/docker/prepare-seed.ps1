param(
    [Parameter(Mandatory = $true)]
    [string]$Archive
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$seedRoot = Join-Path $root "docker\seed"
$postgresTarget = Join-Path $seedRoot "postgres"
$qdrantTarget = Join-Path $seedRoot "qdrant"
$seedRootFull = [IO.Path]::GetFullPath($seedRoot)
foreach ($target in @($postgresTarget, $qdrantTarget)) {
    $targetFull = [IO.Path]::GetFullPath($target)
    if (-not $targetFull.StartsWith($seedRootFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unexpected seed target: $targetFull"
    }
    New-Item -ItemType Directory -Force -Path $targetFull | Out-Null
    Get-ChildItem -LiteralPath $targetFull -File | Remove-Item -Force
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("careernav-seed-" + [Guid]::NewGuid())
try {
    Expand-Archive -LiteralPath $archivePath -DestinationPath $temp
    Copy-Item -LiteralPath (Join-Path $temp "postgres\careernav.dump") -Destination $postgresTarget -Force
    Copy-Item -Path (Join-Path $temp "qdrant\*.snapshot") -Destination $qdrantTarget -Force
    Write-Host "Seed files staged under $seedRoot"
} finally {
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Recurse -Force
    }
}
