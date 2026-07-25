param(
    [Parameter(Mandatory = $true)]
    [string] $MatcPath
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$source = Join-Path $workspace 'core\visual-ui\src\main\filament\visual_unlit.mat'
$assetDirectory = Join-Path $workspace 'core\visual-ui\src\main\assets\materials'
$output = Join-Path $assetDirectory 'visual_unlit.filamat'

New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
& $MatcPath --api all --platform mobile --output $output $source
if ($LASTEXITCODE -ne 0) {
    throw "matc failed with exit code $LASTEXITCODE"
}

$asset = Get-Item -LiteralPath $output
Write-Output "Generated $($asset.FullName) ($($asset.Length) bytes)"
