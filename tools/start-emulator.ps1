[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$ShowWindow,
    [switch]$WipeData,
    [switch]$AllowExistingAndroidProfileArtifacts
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1') `
    -AllowExistingAndroidProfileArtifacts:$AllowExistingAndroidProfileArtifacts

$Port = Resolve-AndroidEmulatorPort -Port $Port
$serial = Get-AndroidEmulatorSerial -Port $Port
$emulatorAdbPort = Get-AndroidEmulatorAdbPort -Port $Port
$emulatorExecutable = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$logPrefix = Join-Path $env:ANDROID_USER_HOME "emulator-$Port"
$logStoragePrefix = Join-Path $androidUserStorageRoot "emulator-$Port"
Assert-WorkspaceStoragePath -Path "$logStoragePrefix.stdout.log"
Assert-WorkspaceStoragePath -Path "$logStoragePrefix.stderr.log"
$occupiedPorts = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @($Port, $emulatorAdbPort)
})
if ($occupiedPorts) {
    throw "Emulator ports $Port/$emulatorAdbPort are already in use."
}

$arguments = @(
    '-avd', $androidAvdName,
    '-port', $Port,
    '-no-audio',
    '-no-boot-anim',
    '-no-snapshot',
    '-gpu', 'swiftshader_indirect',
    '-accel', 'on',
    '-no-metrics'
)

if (-not $ShowWindow) {
    $arguments += '-no-window'
}

if ($WipeData) {
    $arguments += '-wipe-data'
}

$startArguments = @{
    FilePath               = $emulatorExecutable
    ArgumentList           = $arguments
    WorkingDirectory       = $runtimeWorkspaceRoot
    WindowStyle            = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
    RedirectStandardOutput = "$logPrefix.stdout.log"
    RedirectStandardError  = "$logPrefix.stderr.log"
    PassThru               = $true
}

$emulatorRoot = (Join-Path $env:ANDROID_HOME 'emulator').TrimEnd('\') + '\'
$portPattern = "-port\s+$Port(?:\s|$)"
$process = $null

try {
    $adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $adbStartOutput = @(& $adbExecutable -P ([int]$env:ANDROID_ADB_SERVER_PORT) start-server 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Workspace ADB server failed to start: $($adbStartOutput -join ' ')"
    }
    Assert-WorkspaceAdbServerOwnership
    $process = Start-Process @startArguments
    $portDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ($process.HasExited) {
            throw "Android emulator exited during startup with code $($process.ExitCode)."
        }
        $adbListener = Get-NetTCPConnection -State Listen -LocalPort $emulatorAdbPort -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($adbListener) {
            break
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $portDeadline)

    if (-not $adbListener) {
        throw "Android emulator did not open ADB port $emulatorAdbPort."
    }

    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($adbListener.OwningProcess)"
    if (-not $listenerProcess -or
        -not $listenerProcess.ExecutablePath.StartsWith($emulatorRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "ADB port $emulatorAdbPort belongs to unexpected process $($listenerProcess.ExecutablePath)."
    }

    # Keep one workspace-owned ADB server alive during boot. Killing it here makes recent emulator
    # builds immediately relaunch it, creating a loop and transient Windows-profile key files.
    $adbInitializationObserved = $false
    $profileCleanupStableSince = $null
    $profileInitializationTimeoutSeconds = if ($WipeData) { 180 } else { 90 }
    $profileInitializationDeadline = [DateTime]::UtcNow.AddSeconds(
        $profileInitializationTimeoutSeconds
    )
    do {
        if ($process.HasExited) {
            throw "Android emulator exited during profile initialization with code $($process.ExitCode)."
        }

        $workspaceAdbServerProcesses = @(Get-WorkspaceAdbServerProcesses)
        $adbServerListeners = @(
            Get-NetTCPConnection -State Listen -LocalPort ([int]$env:ANDROID_ADB_SERVER_PORT) -ErrorAction SilentlyContinue
        )
        if ($workspaceAdbServerProcesses -or $adbServerListeners) {
            Assert-WorkspaceAdbServerOwnership
            $adbInitializationObserved = $true
        }

        if (-not $AllowExistingAndroidProfileArtifacts -and
            ((Test-Path -LiteralPath $profileAndroidDirectory) -or
                (Test-Path -LiteralPath $profileConsoleToken))) {
            Move-GeneratedAndroidProfileArtifactsToWorkspace -ExpectedEmulatorPort $Port
        }

        $profileArtifactsRemain = (Test-Path -LiteralPath $profileAndroidDirectory) -or
            (Test-Path -LiteralPath $profileConsoleToken)
        $profileArtifactsAccepted =
            $AllowExistingAndroidProfileArtifacts -or -not $profileArtifactsRemain
        if ($adbInitializationObserved -and $profileArtifactsAccepted) {
            if (-not $profileCleanupStableSince) {
                $profileCleanupStableSince = [DateTime]::UtcNow
            } elseif (([DateTime]::UtcNow - $profileCleanupStableSince).TotalSeconds -ge 15) {
                break
            }
        } else {
            $profileCleanupStableSince = $null
        }

        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $profileInitializationDeadline)

    if (-not $adbInitializationObserved -or
        -not $profileCleanupStableSince -or
        ([DateTime]::UtcNow - $profileCleanupStableSince).TotalSeconds -lt 15) {
        throw "Android emulator profile initialization did not become clean and stable within $profileInitializationTimeoutSeconds seconds."
    }
} catch {
    $startupError = $_
    $cleanupFailures = [Collections.Generic.List[string]]::new()

    try {
        $knownStartedProcessIds = [Collections.Generic.HashSet[int]]::new()
        if ($process) {
            [void]$knownStartedProcessIds.Add([int]$process.Id)
        }
        function Get-StartedEmulatorCleanupProcesses {
            $knownProcessIds = @($knownStartedProcessIds)
            $shutdownHelperPattern = if ($knownProcessIds) {
                '-kill\s+(?:{0})(?:\s|$)' -f (
                    ($knownProcessIds | ForEach-Object { [regex]::Escape([string]$_) }) -join '|'
                )
            } else {
                '(?!)'
            }

            @(Get-CimInstance Win32_Process | Where-Object {
                $_.Name -in @('emulator.exe', 'qemu-system-x86_64-headless.exe', 'qemu-system-x86_64.exe') -and
                $_.ExecutablePath -and
                $_.ExecutablePath.StartsWith($emulatorRoot, [StringComparison]::OrdinalIgnoreCase) -and
                (
                    $_.ProcessId -in $knownProcessIds -or
                    $_.CommandLine -match $portPattern -or
                    $_.CommandLine -match $shutdownHelperPattern
                )
            })
        }

        $processStopFailures = [Collections.Generic.List[string]]::new()
        $cleanupStableSince = $null
        $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(8)

        do {
            $startedProcesses = @(Get-StartedEmulatorCleanupProcesses)

            foreach ($startedProcess in $startedProcesses) {
                [void]$knownStartedProcessIds.Add([int]$startedProcess.ProcessId)
                try {
                    Stop-Process -Id $startedProcess.ProcessId -Force -ErrorAction Stop
                } catch {
                    $processStopFailures.Add("PID $($startedProcess.ProcessId): $($_.Exception.Message)")
                }
            }

            $remainingStartedProcesses = @(Get-StartedEmulatorCleanupProcesses)
            $remainingPortListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
                $_.LocalPort -in @($Port, $emulatorAdbPort)
            })
            if (-not $remainingStartedProcesses -and -not $remainingPortListeners) {
                if (-not $cleanupStableSince) {
                    $cleanupStableSince = [DateTime]::UtcNow
                } elseif (([DateTime]::UtcNow - $cleanupStableSince).TotalSeconds -ge 2) {
                    break
                }
            } else {
                $cleanupStableSince = $null
            }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $cleanupDeadline)

        if (-not $cleanupStableSince -or
            ([DateTime]::UtcNow - $cleanupStableSince).TotalSeconds -lt 2 -or
            $remainingStartedProcesses -or
            $remainingPortListeners) {
            $stopFailureDetails = if ($processStopFailures) {
                " Stop errors: $($processStopFailures -join ' | ')"
            } else {
                ''
            }
            throw "Emulator processes or ports $Port/$emulatorAdbPort did not remain clear for two seconds.$stopFailureDetails"
        }
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
        throw "Android emulator startup failed: $($startupError.Exception.Message) Cleanup also failed: $($cleanupFailures -join ' | ')"
    }
    throw $startupError
}

[pscustomobject]@{
    AvdName = $androidAvdName
    Serial = $serial
    ProcessId = $process.Id
    StorageRoot = $workspaceRoot
    RuntimeAlias = $runtimeWorkspaceRoot
    StandardOutput = "$logPrefix.stdout.log"
    StandardError = "$logPrefix.stderr.log"
}
