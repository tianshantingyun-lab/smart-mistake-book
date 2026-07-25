[CmdletBinding()]
param(
    [int]$Port = 0,
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$AdbArguments
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

$Port = Resolve-AndroidEmulatorPort -Port $Port
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$adbServerPort = [int]$env:ANDROID_ADB_SERVER_PORT
$endpoint = Get-AndroidEmulatorEndpoint -Port $Port
$serial = $endpoint
$adbStartedForCommand = $false

try {
    $existingWorkspaceServer = @(
        Get-NetTCPConnection -State Listen -LocalPort $adbServerPort -ErrorAction SilentlyContinue
    )
    if ($existingWorkspaceServer) {
        Assert-WorkspaceAdbServerOwnership
    } else {
        $startServerOutput = @(& $adbExecutable -P $adbServerPort start-server 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "adb start-server failed: $($startServerOutput -join ' ')"
        }
        $adbStartedForCommand = $true
        Assert-WorkspaceAdbServerOwnership
    }

    $connectOutput = @(& $adbExecutable -P $adbServerPort connect $endpoint 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($connectOutput -join "`n") -notmatch '(already )?connected to') {
        throw "adb connect failed: $($connectOutput -join ' ')"
    }

    $output = @(& $adbExecutable -P $adbServerPort -s $serial @AdbArguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output
    if ($exitCode -ne 0) {
        throw "adb command failed with exit code $exitCode"
    }
} finally {
    try {
        if ($adbStartedForCommand) {
            Stop-WorkspaceAdbServer
        }
    } finally {
        Move-GeneratedAndroidProfileArtifactsToWorkspace `
            -Attempts 20 `
            -DelayMilliseconds 250 `
            -ExpectedEmulatorPort $Port
    }
}
