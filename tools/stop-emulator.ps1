[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$AllowExistingAndroidProfileArtifacts
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1') `
    -AllowExistingAndroidProfileArtifacts:$AllowExistingAndroidProfileArtifacts

$Port = Resolve-AndroidEmulatorPort -Port $Port
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$adbServerPort = $env:ANDROID_ADB_SERVER_PORT
$serial = Get-AndroidEmulatorSerial -Port $Port
$emulatorAdbPort = Get-AndroidEmulatorAdbPort -Port $Port
$endpoint = Get-AndroidEmulatorEndpoint -Port $Port
$emulatorRoot = (Join-Path $env:ANDROID_HOME 'emulator').TrimEnd('\') + '\'
$emulatorStorageRoot = (Join-Path $workspaceRoot '.toolchains\android-sdk\emulator').TrimEnd('\') + '\'
$portPattern = "-port\s+$Port(?:\s|$)"

function Test-WorkspaceEmulatorProcess {
    param($Process)

    $Process -and
        $Process.Name -in @('emulator.exe', 'qemu-system-x86_64-headless.exe', 'qemu-system-x86_64.exe') -and
        $Process.ExecutablePath -and
        (
            $Process.ExecutablePath.StartsWith($emulatorRoot, [StringComparison]::OrdinalIgnoreCase) -or
            $Process.ExecutablePath.StartsWith($emulatorStorageRoot, [StringComparison]::OrdinalIgnoreCase)
        )
}

$targetListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @($Port, $emulatorAdbPort)
})
if (-not $targetListeners) {
    throw "No Android emulator is listening on ports $Port/$emulatorAdbPort."
}

$validatedTargetProcessIds = @($targetListeners.OwningProcess | Sort-Object -Unique)
foreach ($targetProcessId in $validatedTargetProcessIds) {
    $targetProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$targetProcessId"
    if (-not (Test-WorkspaceEmulatorProcess $targetProcess) -or
        $targetProcess.CommandLine -notmatch $portPattern) {
        throw "Emulator ports $Port/$emulatorAdbPort belong to unexpected process $($targetProcess.ExecutablePath) (PID $targetProcessId)."
    }
}

$shutdownHelperProcessPattern = '-kill\s+(?:{0})(?:\s|$)' -f (
    ($validatedTargetProcessIds | ForEach-Object { [regex]::Escape([string]$_) }) -join '|'
)

function Get-TargetEmulatorProcesses {
    @(Get-CimInstance Win32_Process | Where-Object {
        (Test-WorkspaceEmulatorProcess $_) -and
        (
            $_.ProcessId -in $validatedTargetProcessIds -or
            $_.CommandLine -match $portPattern -or
            $_.CommandLine -match $shutdownHelperProcessPattern
        )
    })
}

function Stop-TargetEmulatorProcesses {
    param([ValidateRange(0, 30)][int]$GraceSeconds)

    $stableSince = $null
    $graceDeadline = [DateTime]::UtcNow.AddSeconds($GraceSeconds)
    while ($GraceSeconds -gt 0 -and [DateTime]::UtcNow -lt $graceDeadline) {
        $remainingProcesses = @(Get-TargetEmulatorProcesses)
        $remainingListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalPort -in @($Port, $emulatorAdbPort)
        })
        if (-not $remainingProcesses -and -not $remainingListeners) {
            if (-not $stableSince) {
                $stableSince = [DateTime]::UtcNow
            } elseif (([DateTime]::UtcNow - $stableSince).TotalSeconds -ge 2) {
                return
            }
        } else {
            $stableSince = $null
        }
        Start-Sleep -Milliseconds 250
    }

    $stopFailures = [Collections.Generic.List[string]]::new()
    $stableSince = $null
    $forceDeadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        foreach ($remainingProcess in @(Get-TargetEmulatorProcesses)) {
            try {
                Stop-Process -Id $remainingProcess.ProcessId -Force -ErrorAction Stop
            } catch {
                $stopFailures.Add("PID $($remainingProcess.ProcessId): $($_.Exception.Message)")
            }
        }

        $remainingProcesses = @(Get-TargetEmulatorProcesses)
        $remainingListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
            $_.LocalPort -in @($Port, $emulatorAdbPort)
        })
        if (-not $remainingProcesses -and -not $remainingListeners) {
            if (-not $stableSince) {
                $stableSince = [DateTime]::UtcNow
            } elseif (([DateTime]::UtcNow - $stableSince).TotalSeconds -ge 2) {
                return
            }
        } else {
            $stableSince = $null
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $forceDeadline)

    $details = if ($stopFailures) { " Stop errors: $($stopFailures -join ' | ')" } else { '' }
    throw "Android emulator $serial did not stop cleanly.$details"
}

$operationError = $null
$shutdownRequested = $false
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
        Assert-WorkspaceAdbServerOwnership
    }

    & $adbExecutable -P $adbServerPort connect $endpoint 2>$null | Out-Null
    & $adbExecutable -P $adbServerPort -s $serial shell sync 2>$null | Out-Null
    $shutdownRequested = $true
    & $adbExecutable -P $adbServerPort -s $serial shell reboot -p 2>$null | Out-Null
    & $adbExecutable -P $adbServerPort disconnect $endpoint 2>$null | Out-Null
} catch {
    $operationError = $_
}

$cleanupFailures = [Collections.Generic.List[string]]::new()
try {
    Stop-TargetEmulatorProcesses -GraceSeconds $(if ($shutdownRequested) { 25 } else { 0 })
} catch {
    $cleanupFailures.Add($_.Exception.Message)
}
try {
    Stop-WorkspaceAdbServer
} catch {
    $cleanupFailures.Add($_.Exception.Message)
}
try {
    if (-not $AllowExistingAndroidProfileArtifacts) {
        Move-GeneratedAndroidProfileArtifactsToWorkspace -Attempts 20 -DelayMilliseconds 250 -ExpectedEmulatorPort $Port
    }
} catch {
    $cleanupFailures.Add($_.Exception.Message)
}

if (-not $AllowExistingAndroidProfileArtifacts) {
    $outsideWorkspaceArtifacts = @(@($profileAndroidDirectory, $profileConsoleToken) | Where-Object {
        Test-Path -LiteralPath $_
    })
    if ($outsideWorkspaceArtifacts) {
        $cleanupFailures.Add("Profile artifacts remain outside the workspace: $($outsideWorkspaceArtifacts -join ', ')")
    }
}

if ($cleanupFailures) {
    $operationDetails = if ($operationError) { " Operation failed: $($operationError.Exception.Message)" } else { '' }
    throw "Android emulator cleanup failed.$operationDetails Cleanup errors: $($cleanupFailures -join ' | ')"
}
if ($operationError) {
    throw $operationError
}
