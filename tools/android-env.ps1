param(
    [switch]$AllowExistingAndroidProfileArtifacts
)

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not [string]::Equals([IO.Path]::GetPathRoot($workspaceRoot), 'D:\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "Android workspace must remain on D:, found $workspaceRoot"
}

. (Join-Path $PSScriptRoot 'android-workspace-security.ps1')
Assert-AndroidWorkspaceSecurity -WorkspaceRoot $workspaceRoot

# Android toolchain and emulator configuration. Other tools scripts consume these values.
$androidAvdName = 'smart_mistake_book_api_36'
$androidCompileSdkApi = 37
$androidBuildToolsVersion = '37.0.0'
$androidRuntimeApi = 36
$androidDefaultEmulatorPort = 5560
$androidAdbServerPort = 5038
$androidCompilePlatformPackage = "platforms;android-$androidCompileSdkApi"
$androidBuildToolsPackage = "build-tools;$androidBuildToolsVersion"
$androidRuntimeSystemImagePackage = "system-images;android-$androidRuntimeApi;google_apis;x86_64"

function Resolve-AndroidEmulatorPort {
    param([int]$Port = 0)

    $resolvedPort = if ($Port -eq 0) { $androidDefaultEmulatorPort } else { $Port }
    if ($resolvedPort -lt 5554 -or $resolvedPort -gt 5584) {
        throw 'The Android emulator console port must be between 5554 and 5584.'
    }
    if ($resolvedPort % 2 -ne 0) {
        throw 'The Android emulator console port must be even.'
    }

    [int]$resolvedPort
}

function Get-AndroidEmulatorSerial {
    param([int]$Port = 0)

    $resolvedPort = Resolve-AndroidEmulatorPort -Port $Port
    "emulator-$resolvedPort"
}

function Get-AndroidEmulatorAdbPort {
    param([int]$Port = 0)

    $resolvedPort = Resolve-AndroidEmulatorPort -Port $Port
    $resolvedPort + 1
}

function Get-AndroidEmulatorEndpoint {
    param([int]$Port = 0)

    $adbPort = Get-AndroidEmulatorAdbPort -Port $Port
    "127.0.0.1:$adbPort"
}

function Assert-WorkspaceStoragePath {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $workspacePrefix = $workspaceRoot.TrimEnd('\') + '\'
    if (-not [string]::Equals($fullPath, $workspaceRoot, [StringComparison]::OrdinalIgnoreCase) -and
        -not $fullPath.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Writable path escapes the workspace: $fullPath"
    }

    $relativePath = [IO.Path]::GetRelativePath($workspaceRoot, $fullPath)
    $pathToInspect = $workspaceRoot
    $segments = if ($relativePath -eq '.') { @() } else { $relativePath -split '[\\/]' }
    foreach ($segment in @('.') + $segments) {
        if ($segment -ne '.') {
            $pathToInspect = Join-Path $pathToInspect $segment
        }
        if (-not (Test-Path -LiteralPath $pathToInspect)) {
            break
        }

        $pathItem = Get-Item -Force -LiteralPath $pathToInspect
        if (($pathItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Writable workspace path contains a reparse point: $pathToInspect"
        }
    }
}

$androidSdkStorageRoot = Join-Path $workspaceRoot '.toolchains\android-sdk'
$gradleStorageRoot = Join-Path $workspaceRoot '.toolchains\gradle\gradle-9.6.1'
$androidUserStorageRoot = Join-Path $workspaceRoot '.android'
$workspaceWritablePaths = @(
    $workspaceRoot,
    $androidSdkStorageRoot,
    $gradleStorageRoot,
    $androidUserStorageRoot,
    (Join-Path $androidUserStorageRoot 'avd'),
    (Join-Path $androidUserStorageRoot 'windows-profile-artifacts'),
    (Join-Path $workspaceRoot '.gradle'),
    (Join-Path $workspaceRoot '.tmp')
)
foreach ($writablePath in $workspaceWritablePaths) {
    Assert-WorkspaceStoragePath -Path $writablePath
}

foreach ($writableRoot in @(
    (Join-Path $workspaceRoot '.android'),
    (Join-Path $workspaceRoot '.gradle'),
    (Join-Path $workspaceRoot '.tmp'),
    (Join-Path $workspaceRoot '.toolchains')
)) {
    if (-not (Test-Path -LiteralPath $writableRoot)) {
        continue
    }
    $reparsePoints = @(
        Get-ChildItem -Force -Recurse -LiteralPath $writableRoot -Attributes ReparsePoint -ErrorAction Stop
    )
    if ($reparsePoints) {
        throw "Writable workspace tree contains reparse points: $($reparsePoints.FullName -join ', ')"
    }
}

# Java worker argfiles and QEMU corrupt non-ASCII executable/classpath entries on this host.
# T: is reserved for an ASCII alias of this same physical D: workspace; S: remains available for
# the independently managed Android SDK alias.
$systemDirectory = [Environment]::SystemDirectory
$substExecutable = Join-Path $systemDirectory 'subst.exe'
$fsutilExecutable = Join-Path $systemDirectory 'fsutil.exe'
foreach ($systemTool in @($substExecutable, $fsutilExecutable)) {
    if (-not (Test-Path -LiteralPath $systemTool -PathType Leaf)) {
        throw "Required Windows system tool is missing: $systemTool"
    }
}
$runtimeDrive = 'T:'
$runtimeDriveRoot = "$runtimeDrive\"
$storageProbe = Join-Path $workspaceRoot 'tools\android-env.ps1'
$runtimeProbe = $runtimeDriveRoot + 'tools\android-env.ps1'

if (-not (Test-Path -LiteralPath $runtimeDriveRoot)) {
    & $substExecutable $runtimeDrive $workspaceRoot
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $runtimeDriveRoot)) {
        throw "Unable to map $runtimeDrive to $workspaceRoot"
    }
}

if (-not (Test-Path -LiteralPath $runtimeProbe)) {
    throw "$runtimeDrive is already in use and does not point to this workspace"
}

$substProcessInfo = [Diagnostics.ProcessStartInfo]::new()
$substProcessInfo.FileName = $substExecutable
$substProcessInfo.UseShellExecute = $false
$substProcessInfo.RedirectStandardOutput = $true
$substProcessInfo.RedirectStandardError = $true
$substProcessInfo.StandardOutputEncoding = [Text.Encoding]::GetEncoding(
    [Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage
)
$substProcess = [Diagnostics.Process]::Start($substProcessInfo)
$substOutput = $substProcess.StandardOutput.ReadToEnd()
$substError = $substProcess.StandardError.ReadToEnd()
$substProcess.WaitForExit()
if ($substProcess.ExitCode -ne 0) {
    throw "Unable to inspect $runtimeDrive mapping: $substError"
}

$mappingPrefix = '{0}\: => ' -f $runtimeDrive
$mappingLine = @($substOutput -split "`r?`n" | Where-Object {
    $_.StartsWith($mappingPrefix, [StringComparison]::OrdinalIgnoreCase)
}) | Select-Object -First 1
if (-not $mappingLine) {
    throw "Unable to verify $runtimeDrive mapping"
}

$mappedWorkspaceRoot = [IO.Path]::GetFullPath($mappingLine.Substring($mappingPrefix.Length)).TrimEnd('\')
$expectedWorkspaceRoot = [IO.Path]::GetFullPath($workspaceRoot).TrimEnd('\')
if (-not [string]::Equals($mappedWorkspaceRoot, $expectedWorkspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "$runtimeDrive maps to $mappedWorkspaceRoot instead of $expectedWorkspaceRoot"
}

$storageProbeId = & $fsutilExecutable file queryfileid $storageProbe 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Unable to identify workspace file $storageProbe"
}
$runtimeProbeId = & $fsutilExecutable file queryfileid $runtimeProbe 2>$null
if ($LASTEXITCODE -ne 0 -or $storageProbeId -ne $runtimeProbeId) {
    throw "$runtimeDrive is already in use and does not point to this workspace"
}

$runtimeWorkspaceRoot = $runtimeDriveRoot
$androidSdkRoot = Join-Path $runtimeWorkspaceRoot '.toolchains\android-sdk'
$gradleRoot = Join-Path $runtimeWorkspaceRoot '.toolchains\gradle\gradle-9.6.1'

$env:ANDROID_HOME = $androidSdkRoot
$env:ANDROID_SDK_ROOT = $androidSdkRoot
$env:ANDROID_USER_HOME = Join-Path $runtimeWorkspaceRoot '.android'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $env:ANDROID_USER_HOME 'avd'
$env:ADB_VENDOR_KEYS = Join-Path $env:ANDROID_USER_HOME 'adbkey'
$env:ANDROID_ADB_LOG_PATH = Join-Path $env:ANDROID_USER_HOME 'adb.log'
$env:ANDROID_ADB_SERVER_PORT = [string]$androidAdbServerPort
$env:GRADLE_USER_HOME = Join-Path $runtimeWorkspaceRoot '.gradle'
$env:TEMP = Join-Path $runtimeWorkspaceRoot '.tmp'
$env:TMP = $env:TEMP

# These legacy variables conflict with ANDROID_USER_HOME in recent SDK tools.
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue

# Android's Windows launchers and AGP still consult the JVM user home for
# caches, AVD metadata and the default debug keystore. The explicit trailing
# dot keeps the value absolute without ending the command-line argument in a
# backslash.
$workspaceJvmUserHome = "$runtimeWorkspaceRoot."
$workspaceUserHomeOption = "-Duser.home=$workspaceJvmUserHome"
$env:AVDMANAGER_OPTS = $workspaceUserHomeOption
$env:SDKMANAGER_OPTS = $workspaceUserHomeOption
$env:GRADLE_OPTS = $workspaceUserHomeOption

$programFilesRoot = [IO.Path]::GetFullPath(
    [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
).TrimEnd('\') + '\'
$javacCommand = @(Get-Command javac -CommandType Application -ErrorAction Stop) | Where-Object {
    $_.Source -and $_.Source.StartsWith($programFilesRoot, [StringComparison]::OrdinalIgnoreCase)
} | Select-Object -First 1
if (-not $javacCommand) {
    throw 'A system-installed JDK under Program Files is required.'
}
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javacCommand.Source)
$javaBin = Join-Path $env:JAVA_HOME 'bin'
foreach ($javaTool in @('java.exe', 'javac.exe')) {
    $javaToolPath = Join-Path $javaBin $javaTool
    if (-not (Test-Path -LiteralPath $javaToolPath -PathType Leaf)) {
        throw "Required JDK tool is missing: $javaToolPath"
    }
}

$toolPaths = @(
    $javaBin
    (Join-Path $androidSdkRoot 'platform-tools')
    (Join-Path $androidSdkRoot 'emulator')
    (Join-Path $androidSdkRoot 'cmdline-tools\latest\bin')
    (Join-Path $gradleRoot 'bin')
)

$currentPaths = $env:Path.Split(
    [System.IO.Path]::PathSeparator,
    [System.StringSplitOptions]::RemoveEmptyEntries
)
$env:Path = ($toolPaths + ($currentPaths | Where-Object { $toolPaths -notcontains $_ })) -join [System.IO.Path]::PathSeparator

$profileRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
$profileAndroidDirectory = Join-Path $profileRoot '.android'
$profileConsoleToken = Join-Path $profileRoot '.emulator_console_auth_token'
$profileAndroidDirectoryExists =
    Test-Path -LiteralPath $profileAndroidDirectory -PathType Container
$profileAndroidDirectoryHasArtifacts =
    $profileAndroidDirectoryExists -and
    (Get-ChildItem -Force -LiteralPath $profileAndroidDirectory | Select-Object -First 1)
$preexistingProfileArtifacts = @(
    @(
        $profileAndroidDirectoryHasArtifacts ? $profileAndroidDirectory : $null
        (Test-Path -LiteralPath $profileConsoleToken) ? $profileConsoleToken : $null
    ) | Where-Object { $_ }
)
if ($preexistingProfileArtifacts -and -not $AllowExistingAndroidProfileArtifacts) {
    throw "Refusing to use Android tools while profile artifacts exist outside the workspace: $($preexistingProfileArtifacts -join ', ')"
}

function Get-ExternalAndroidProfileSnapshot {
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($rootPath in @($profileAndroidDirectory, $profileConsoleToken)) {
        if (-not (Test-Path -LiteralPath $rootPath)) {
            $lines.Add("MISSING|$rootPath")
            continue
        }
        $rootItem = Get-Item -Force -LiteralPath $rootPath
        $items = @($rootItem)
        if ($rootItem.PSIsContainer) {
            $items += @(Get-ChildItem -Force -Recurse -LiteralPath $rootPath)
        }
        foreach ($item in $items) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing to inspect Android profile reparse point: $($item.FullName)"
            }
            $relativePath = [IO.Path]::GetRelativePath($profileRoot, $item.FullName)
            if ($item.PSIsContainer) {
                $lines.Add("D|$relativePath|$($item.LastWriteTimeUtc.Ticks)")
            } else {
                $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash
                $lines.Add(
                    "F|$relativePath|$($item.Length)|$($item.LastWriteTimeUtc.Ticks)|$hash"
                )
            }
        }
    }
    $lines.Sort([StringComparer]::Ordinal)
    $canonical = [Text.Encoding]::UTF8.GetBytes($lines -join "`n")
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        ($sha256.ComputeHash($canonical) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha256.Dispose()
    }
}

function Assert-ExternalAndroidProfileUnchanged {
    if ($null -eq $preexistingAndroidProfileSnapshot) {
        return
    }
    $afterSnapshot = Get-ExternalAndroidProfileSnapshot
    if (-not [string]::Equals(
        $afterSnapshot,
        $preexistingAndroidProfileSnapshot,
        [StringComparison]::Ordinal
    )) {
        throw 'Android device work changed pre-existing user profile artifacts outside the workspace.'
    }
}

$preexistingAndroidProfileSnapshot = if (
    $AllowExistingAndroidProfileArtifacts -and
    ($profileAndroidDirectoryExists -or (Test-Path -LiteralPath $profileConsoleToken))
) {
    Get-ExternalAndroidProfileSnapshot
} else {
    $null
}
$androidProfileArtifactTrackingEnabled =
    -not $profileAndroidDirectoryExists -and
    -not (Test-Path -LiteralPath $profileConsoleToken)
$profileArtifactTrackingStartedAt = [DateTime]::UtcNow.AddSeconds(-1)

function Assert-NoConflictingAndroidProcesses {
    param([int]$ExpectedEmulatorPort = 0)

    $runtimeEmulatorRoot = (Join-Path $env:ANDROID_HOME 'emulator').TrimEnd('\') + '\'
    $storageEmulatorRoot = (Join-Path $workspaceRoot '.toolchains\android-sdk\emulator').TrimEnd('\') + '\'
    $runtimeAdbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $storageAdbExecutable = Join-Path $workspaceRoot '.toolchains\android-sdk\platform-tools\adb.exe'
    $portPattern = if ($ExpectedEmulatorPort -gt 0) {
        "-port\s+$ExpectedEmulatorPort(?:\s|$)"
    } else {
        $null
    }
    $expectedAdbPattern = if ($ExpectedEmulatorPort -gt 0) {
        $expectedSerial = [regex]::Escape((Get-AndroidEmulatorSerial -Port $ExpectedEmulatorPort))
        "(?:^|\s)(?:-s\s+$expectedSerial|-P\s+$($env:ANDROID_ADB_SERVER_PORT)|-L\s+tcp:$($env:ANDROID_ADB_SERVER_PORT))(?:\s|$)"
    } else {
        $null
    }
    $conflictingProcesses = @(Get-CimInstance Win32_Process | Where-Object {
        if ($_.Name -eq 'adb.exe') {
            $isExpectedWorkspaceAdb = $ExpectedEmulatorPort -gt 0 -and
                $_.ExecutablePath -and
                (
                    [string]::Equals($_.ExecutablePath, $runtimeAdbExecutable, [StringComparison]::OrdinalIgnoreCase) -or
                    [string]::Equals($_.ExecutablePath, $storageAdbExecutable, [StringComparison]::OrdinalIgnoreCase)
                ) -and
                $_.CommandLine -match $expectedAdbPattern
            return -not $isExpectedWorkspaceAdb
        }
        if ($_.Name -notin @('emulator.exe', 'qemu-system-x86_64-headless.exe', 'qemu-system-x86_64.exe')) {
            return $false
        }

        $isExpectedWorkspaceEmulator = $ExpectedEmulatorPort -gt 0 -and
            $_.ExecutablePath -and
            (
                $_.ExecutablePath.StartsWith($runtimeEmulatorRoot, [StringComparison]::OrdinalIgnoreCase) -or
                $_.ExecutablePath.StartsWith($storageEmulatorRoot, [StringComparison]::OrdinalIgnoreCase)
            ) -and
            $_.CommandLine -match $portPattern
        -not $isExpectedWorkspaceEmulator
    })
    if ($conflictingProcesses) {
        $processDetails = @($conflictingProcesses | ForEach-Object {
            "PID=$($_.ProcessId), Name=$($_.Name), Path=$($_.ExecutablePath), CommandLine=$($_.CommandLine)"
        }) -join ' | '
        throw "Refusing to attribute profile artifacts while other Android processes are active: $processDetails"
    }
}

# ADB and the emulator still touch the Windows profile. Converge files created by
# this run into the single workspace identity instead of retaining private-key copies.
function Test-AndroidProfileArtifactProducerActive {
    [bool](@(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @('adb.exe', 'emulator.exe', 'qemu-system-x86_64-headless.exe', 'qemu-system-x86_64.exe')
    }).Count)
}

function Move-OrDiscardGeneratedAndroidProfileFile {
    param(
        [Parameter(Mandatory)][string]$SourcePath,
        [Parameter(Mandatory)][string]$DestinationPath
    )

    $sourceItem = Get-Item -Force -LiteralPath $SourcePath
    if ($sourceItem.PSIsContainer -or
        ($sourceItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $sourceItem.CreationTimeUtc -lt $profileArtifactTrackingStartedAt) {
        throw "Refusing to process unsafe Android profile artifact: $SourcePath"
    }

    Assert-WorkspaceStoragePath -Path $DestinationPath
    if (Test-Path -LiteralPath $DestinationPath) {
        $destinationItem = Get-Item -Force -LiteralPath $DestinationPath
        if ($destinationItem.PSIsContainer -or
            ($destinationItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $destinationItem.Length -le 0) {
            throw "Workspace Android profile destination is unsafe: $DestinationPath"
        }
        Remove-Item -Force -LiteralPath $SourcePath
        return
    }

    Move-Item -LiteralPath $SourcePath -Destination $DestinationPath
}

function Move-GeneratedAndroidProfileArtifactsToWorkspace {
    param(
        [ValidateRange(1, 100)]
        [int]$Attempts = 1,
        [ValidateRange(0, 5000)]
        [int]$DelayMilliseconds = 0,
        [ValidateRange(0, 5584)]
        [int]$ExpectedEmulatorPort = 0
    )

    $allowedAndroidProfileFiles = @('adbkey', 'adbkey.pub', 'adb_known_hosts.pb', 'adb_known_hosts.pb.temp')

    $profileArtifactsExist = (Test-Path -LiteralPath $profileAndroidDirectory) -or
        (Test-Path -LiteralPath $profileConsoleToken)
    if (-not $profileArtifactsExist) {
        if (-not (Test-AndroidProfileArtifactProducerActive)) {
            return
        }
        if ($DelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $DelayMilliseconds
        }
    }

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        $moveAndroidDirectory = Test-Path -LiteralPath $profileAndroidDirectory
        $moveConsoleToken = Test-Path -LiteralPath $profileConsoleToken

        if (-not $moveAndroidDirectory -and -not $moveConsoleToken) {
            if (-not (Test-AndroidProfileArtifactProducerActive) -or $attempt -eq $Attempts) {
                return
            }
            if ($DelayMilliseconds -gt 0) {
                Start-Sleep -Milliseconds $DelayMilliseconds
            }
            continue
        }

        Assert-NoConflictingAndroidProcesses -ExpectedEmulatorPort $ExpectedEmulatorPort
        if ($moveAndroidDirectory) {
            $profileDirectoryItem = Get-Item -Force -LiteralPath $profileAndroidDirectory
            if (($profileDirectoryItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing to move reparse point $profileAndroidDirectory"
            }
            $profileEntries = @(Get-ChildItem -Force -LiteralPath $profileAndroidDirectory)
            $unexpectedEntries = @($profileEntries | Where-Object {
                $_.PSIsContainer -or $_.Name -notin $allowedAndroidProfileFiles -or
                ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                $_.CreationTimeUtc -lt $profileArtifactTrackingStartedAt
            })
            if ($unexpectedEntries) {
                throw "Refusing to move unrecognized Android profile artifacts: $($unexpectedEntries.Name -join ', ')"
            }

            foreach ($profileEntry in $profileEntries) {
                $destinationPath = Join-Path $androidUserStorageRoot $profileEntry.Name
                Move-OrDiscardGeneratedAndroidProfileFile `
                    -SourcePath $profileEntry.FullName `
                    -DestinationPath $destinationPath
            }

            if ((Get-ChildItem -Force -LiteralPath $profileAndroidDirectory | Measure-Object).Count -eq 0) {
                Remove-Item -LiteralPath $profileAndroidDirectory
            }
        }

        if ($moveConsoleToken) {
            $consoleTokenItem = Get-Item -Force -LiteralPath $profileConsoleToken
            if (($consoleTokenItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                $consoleTokenItem.CreationTimeUtc -lt $profileArtifactTrackingStartedAt) {
                throw "Refusing to move console token not created by this run: $profileConsoleToken"
            }

            Move-OrDiscardGeneratedAndroidProfileFile `
                -SourcePath $profileConsoleToken `
                -DestinationPath (Join-Path $androidUserStorageRoot 'emulator_console_auth_token')
        }

        if ($attempt -lt $Attempts -and $DelayMilliseconds -gt 0) {
            Start-Sleep -Milliseconds $DelayMilliseconds
        }
    }
}

function Assert-WorkspaceAdbServerPortAvailable {
    $serverPort = [int]$env:ANDROID_ADB_SERVER_PORT
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue)
    if ($listeners) {
        throw "Dedicated workspace ADB server port $serverPort is already in use by PID $($listeners.OwningProcess -join ', ')."
    }
}

function Assert-WorkspaceAdbServerOwnership {
    $serverPort = [int]$env:ANDROID_ADB_SERVER_PORT
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue)
    if (-not $listeners) {
        throw "Workspace ADB server is not listening on port $serverPort."
    }

    $runtimeAdbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $storageAdbExecutable = Join-Path $workspaceRoot '.toolchains\android-sdk\platform-tools\adb.exe'
    foreach ($processId in @($listeners.OwningProcess | Sort-Object -Unique)) {
        $serverProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
        $isWorkspaceAdb = $false
        foreach ($expectedAdbExecutable in @($runtimeAdbExecutable, $storageAdbExecutable)) {
            if ($serverProcess -and [string]::Equals(
                $serverProcess.ExecutablePath,
                $expectedAdbExecutable,
                [StringComparison]::OrdinalIgnoreCase
            )) {
                $isWorkspaceAdb = $true
                break
            }
        }
        if (-not $isWorkspaceAdb) {
            throw "ADB server port $serverPort belongs to unexpected process $($serverProcess.ExecutablePath) (PID $processId)."
        }
    }
}

function Get-WorkspaceAdbProcesses {
    $runtimeAdbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $storageAdbExecutable = Join-Path $workspaceRoot '.toolchains\android-sdk\platform-tools\adb.exe'

    @(Get-CimInstance Win32_Process -Filter "Name='adb.exe'" | Where-Object {
        (
            [string]::Equals($_.ExecutablePath, $runtimeAdbExecutable, [StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($_.ExecutablePath, $storageAdbExecutable, [StringComparison]::OrdinalIgnoreCase)
        )
    })
}

function Get-WorkspaceAdbServerProcesses {
    @(Get-WorkspaceAdbProcesses | Where-Object {
        $_.CommandLine -match '\bfork-server\s+server\b'
    })
}

function Stop-WorkspaceAdbServer {
    $adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    $serverPort = [int]$env:ANDROID_ADB_SERVER_PORT
    $killRequestedProcessIds = [Collections.Generic.HashSet[int]]::new()
    $stableSince = $null
    # The booting emulator can relaunch its workspace ADB server briefly; wait it out and require stability.
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue)
        $workspaceAdbServerProcesses = @(Get-WorkspaceAdbServerProcesses)
        $listenerProcessIds = @($listeners.OwningProcess | Sort-Object -Unique)
        $orphanedServerProcesses = @($workspaceAdbServerProcesses | Where-Object {
            $_.ProcessId -notin $listenerProcessIds
        })
        foreach ($orphanedServerProcess in $orphanedServerProcesses) {
            Stop-Process -Id $orphanedServerProcess.ProcessId -Force -ErrorAction Stop
        }

        if ($listeners) {
            Assert-WorkspaceAdbServerOwnership
            $newServerProcessIds = @($listeners.OwningProcess | Sort-Object -Unique | Where-Object {
                -not $killRequestedProcessIds.Contains([int]$_)
            })
            foreach ($processId in $newServerProcessIds) {
                [void]$killRequestedProcessIds.Add([int]$processId)
            }

            if ($newServerProcessIds) {
                $killServerOutput = @(& $adbExecutable -P $serverPort kill-server 2>&1)
                if ($LASTEXITCODE -ne 0) {
                    throw "adb kill-server failed: $($killServerOutput -join ' ')"
                }
            }
            $stableSince = $null
        }

        $workspaceAdbServerProcesses = @(Get-WorkspaceAdbServerProcesses)
        $listenerProcessIds = @($listeners.OwningProcess | Sort-Object -Unique)
        $orphanedServerProcesses = @($workspaceAdbServerProcesses | Where-Object {
            $_.ProcessId -notin $listenerProcessIds
        })
        foreach ($orphanedServerProcess in $orphanedServerProcesses) {
            Stop-Process -Id $orphanedServerProcess.ProcessId -Force -ErrorAction Stop
        }

        $workspaceAdbProcesses = @(Get-WorkspaceAdbProcesses)
        if (-not $listeners -and -not $workspaceAdbProcesses) {
            if (-not $stableSince) {
                $stableSince = [DateTime]::UtcNow
            } elseif (([DateTime]::UtcNow - $stableSince).TotalSeconds -ge 3) {
                return
            }
        } else {
            $stableSince = $null
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Workspace ADB server on port $serverPort did not remain stopped for three seconds."
}
