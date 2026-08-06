[CmdletBinding()]
param(
    [int]$EmulatorPort = 0,
    [Parameter(Mandatory, Position = 0, ValueFromRemainingArguments)]
    [string[]]$GradleArguments
)

$ErrorActionPreference = 'Stop'

$usesAndroidDevice = $EmulatorPort -gt 0
$deviceTaskPattern = '(?i)(?:^|:)(?:connected|device|managedDevice|install|uninstall)[^:]*$'
$unscopedDeviceTasks = @($GradleArguments | Where-Object { $_ -match $deviceTaskPattern })
if (-not $usesAndroidDevice -and $unscopedDeviceTasks) {
    throw "Android device Gradle tasks require -EmulatorPort: $($unscopedDeviceTasks -join ', ')"
}
if ($GradleArguments -match '^-Duser\.home=') {
    throw 'Gradle user.home is owned by the workspace wrapper and cannot be overridden.'
}

# Existing user Android files are never moved or deleted. Device work snapshots them and verifies
# that workspace-scoped ADB/Gradle execution left them byte-for-byte unchanged.
. (Join-Path $PSScriptRoot 'android-env.ps1') -AllowExistingAndroidProfileArtifacts

# AGP 9 rejects the deprecated environment variable when the supported JVM
# property is also present. The workspace property keeps Android preferences on D:.
$workspaceAndroidUserHome = $env:ANDROID_USER_HOME
Remove-Item Env:ANDROID_USER_HOME -ErrorAction SilentlyContinue

$resolvedEmulatorPort = if ($EmulatorPort -gt 0) {
    Resolve-AndroidEmulatorPort -Port $EmulatorPort
} else {
    0
}
if ($resolvedEmulatorPort -gt 0 -and $androidProfileArtifactTrackingEnabled) {
    Assert-NoConflictingAndroidProcesses -ExpectedEmulatorPort $resolvedEmulatorPort
}
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$gradleExecutable = Join-Path $gradleRoot 'bin\gradle.bat'
$adbServerPort = [int]$env:ANDROID_ADB_SERVER_PORT

Push-Location $runtimeWorkspaceRoot
try {
    if ($resolvedEmulatorPort -gt 0) {
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
            Assert-WorkspaceAdbServerOwnership
        }

        $endpoint = Get-AndroidEmulatorEndpoint -Port $resolvedEmulatorPort
        $connectOutput = @(& $adbExecutable -P $adbServerPort connect $endpoint 2>&1)
        if ($LASTEXITCODE -ne 0 -or ($connectOutput -join "`n") -notmatch '(already )?connected to') {
            throw "adb connect failed: $($connectOutput -join ' ')"
        }
        # ADB on this Windows host exposes the explicitly connected emulator by its TCP endpoint.
        # Pin Gradle to that online transport instead of the stale auto-discovered emulator alias.
        $env:ANDROID_SERIAL = $endpoint
    }

    # Invoke the exact executable whose digest was verified by android-env.ps1.
    # A bare command name could be shadowed by a PowerShell function or alias.
    # GRADLE_OPTS configures the lightweight client only. Pass user.home as a
    # Gradle system property as well so the single-use daemon and AGP resolve
    # the debug keystore under the workspace rather than the Windows profile.
    & $gradleExecutable --no-daemon $workspaceUserHomeOption @GradleArguments
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    try {
        # AGP can start an additional workspace-owned ADB server on the default
        # port even when this wrapper connected through the dedicated port.
        # Always stop workspace ADB processes after device work so they cannot
        # recreate keys in the Windows profile after the command returns.
        if ($resolvedEmulatorPort -gt 0) {
            Stop-WorkspaceAdbServer
            Assert-ExternalAndroidProfileUnchanged
        }
    } finally {
        $env:ANDROID_USER_HOME = $workspaceAndroidUserHome
        if ($androidProfileArtifactTrackingEnabled) {
            Move-GeneratedAndroidProfileArtifactsToWorkspace `
                -Attempts 20 `
                -DelayMilliseconds 250 `
                -ExpectedEmulatorPort $resolvedEmulatorPort
        }
    }
}

if ($gradleExitCode -ne 0) {
    throw "Gradle failed with exit code $gradleExitCode"
}
