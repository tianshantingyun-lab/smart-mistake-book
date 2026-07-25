[CmdletBinding()]
param(
    [int]$Port = 0,
    [ValidateRange(10, 600)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

$Port = Resolve-AndroidEmulatorPort -Port $Port
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$adbServerPort = $env:ANDROID_ADB_SERVER_PORT
$serial = Get-AndroidEmulatorSerial -Port $Port
$emulatorAdbPort = Get-AndroidEmulatorAdbPort -Port $Port
$endpoint = Get-AndroidEmulatorEndpoint -Port $Port
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$state = ''
$bootCompleted = ''

try {
    Assert-WorkspaceAdbServerPortAvailable

    $adbListener = Get-NetTCPConnection -State Listen -LocalPort $emulatorAdbPort -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $adbListener) {
        throw "No Android emulator is listening on ADB port $emulatorAdbPort."
    }

    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($adbListener.OwningProcess)"
    $emulatorRoot = (Join-Path $env:ANDROID_HOME 'emulator').TrimEnd('\') + '\'
    if (-not $listenerProcess -or
        -not $listenerProcess.ExecutablePath.StartsWith($emulatorRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "ADB port $emulatorAdbPort belongs to unexpected process $($listenerProcess.ExecutablePath)."
    }

    $startServerOutput = @(& $adbExecutable -P $adbServerPort start-server 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "adb start-server failed: $($startServerOutput -join ' ')"
    }
    Assert-WorkspaceAdbServerOwnership

    $connectOutput = @(& $adbExecutable -P $adbServerPort connect $endpoint 2>&1)
    if ($LASTEXITCODE -ne 0 -or ($connectOutput -join "`n") -notmatch '(already )?connected to') {
        throw "adb connect failed: $($connectOutput -join ' ')"
    }

    do {
        $stateOutput = @(& $adbExecutable -P $adbServerPort -s $serial get-state 2>$null)
        $state = if ($stateOutput) { $stateOutput[-1].Trim() } else { '' }
        if ($state -eq 'device') {
            $bootOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell getprop sys.boot_completed 2>$null)
            $bootCompleted = if ($bootOutput) { $bootOutput[-1].Trim() } else { '' }
            if ($bootCompleted -eq '1') {
                break
            }
        }

        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($state -ne 'device' -or $bootCompleted -ne '1') {
        throw "Android emulator $serial did not finish booting within $TimeoutSeconds seconds."
    }

    $avdNameOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell getprop ro.boot.qemu.avd_name 2>$null)
    $avdName = if ($avdNameOutput) { $avdNameOutput[-1].Trim() } else { '' }
    if ($avdName -ne $androidAvdName) {
        throw "Unexpected Android virtual device on ${serial}: $avdName"
    }

    $packageManagerOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell cmd package path android 2>$null)
    $packageManagerPath = if ($packageManagerOutput) { $packageManagerOutput[-1].Trim() } else { '' }
    if ($packageManagerPath -notmatch '^package:') {
        throw "Android package manager is not ready on $serial."
    }

    $apiLevelOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell getprop ro.build.version.sdk 2>$null)
    $androidReleaseOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell getprop ro.build.version.release 2>$null)
    $modelOutput = @(& $adbExecutable -P $adbServerPort -s $serial shell getprop ro.product.model 2>$null)
    $apiLevel = if ($apiLevelOutput) { $apiLevelOutput[-1].Trim() } else { '' }
    $androidRelease = if ($androidReleaseOutput) { $androidReleaseOutput[-1].Trim() } else { '' }
    $model = if ($modelOutput) { $modelOutput[-1].Trim() } else { '' }

    if ($apiLevel -ne [string]$androidRuntimeApi -or -not $androidRelease -or -not $model) {
        throw "Unexpected Android system properties on $serial."
    }

    [pscustomobject]@{
        Serial = $serial
        State = $state
        BootCompleted = $bootCompleted
        ApiLevel = $apiLevel
        AndroidRelease = $androidRelease
        Model = $model
        PackageManager = $packageManagerPath
    }
} finally {
    try {
        Stop-WorkspaceAdbServer
    } finally {
        Move-GeneratedAndroidProfileArtifactsToWorkspace -Attempts 20 -DelayMilliseconds 250 -ExpectedEmulatorPort $Port
    }
}
