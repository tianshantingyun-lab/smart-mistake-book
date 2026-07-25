[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [int]$EmulatorPort = 5560,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName = 'com.tingyun.smartmistakebook.offline',
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory = 'D:\智能错题本\design\qa\room-live',
    [switch]$ResetData,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'

$androidEnvironmentScript = Join-Path $PSScriptRoot 'android-env.ps1'
if (-not (Test-Path -LiteralPath $androidEnvironmentScript -PathType Leaf)) {
    throw "Missing Android environment script: $androidEnvironmentScript"
}
. $androidEnvironmentScript

$EmulatorPort = Resolve-AndroidEmulatorPort -Port $EmulatorPort
$adbExecutable = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$adbServerPort = [int]$env:ANDROID_ADB_SERVER_PORT
$endpoint = Get-AndroidEmulatorEndpoint -Port $EmulatorPort
$expectedEndpoint = '127.0.0.1:{0}' -f ($EmulatorPort + 1)

if ($adbServerPort -ne 5038) {
    throw "Smoke QA requires the dedicated ADB server port 5038, found $adbServerPort."
}
if (-not [string]::Equals($endpoint, $expectedEndpoint, [StringComparison]::Ordinal)) {
    throw "Unexpected emulator endpoint $endpoint; expected $expectedEndpoint."
}
if (-not (Test-Path -LiteralPath $adbExecutable -PathType Leaf)) {
    throw "Missing workspace ADB executable: $adbExecutable"
}
foreach ($requiredCommand in @('Get-CimInstance', 'Get-NetTCPConnection')) {
    if (-not (Get-Command $requiredCommand -ErrorAction SilentlyContinue)) {
        throw "Missing required PowerShell command: $requiredCommand"
    }
}
foreach ($requiredFunction in @(
    'Assert-NoConflictingAndroidProcesses',
    'Assert-WorkspaceAdbServerOwnership',
    'Assert-WorkspaceAdbServerPortAvailable',
    'Assert-WorkspaceStoragePath',
    'Move-GeneratedAndroidProfileArtifactsToWorkspace',
    'Stop-WorkspaceAdbServer'
)) {
    if (-not (Get-Command $requiredFunction -CommandType Function -ErrorAction SilentlyContinue)) {
        throw "android-env.ps1 did not define required function $requiredFunction."
    }
}

$outputStorageRoot = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $workspaceRoot $OutputDirectory))
}
Assert-WorkspaceStoragePath -Path $outputStorageRoot

$validationRequested = $ValidateOnly -or
    ($PSBoundParameters.ContainsKey('WhatIf') -and [bool]$PSBoundParameters['WhatIf'])
if ($validationRequested) {
    [pscustomobject]@{
        Status = 'Valid'
        Mode = if ($ValidateOnly) { 'ValidateOnly' } else { 'WhatIf' }
        EmulatorPort = $EmulatorPort
        Endpoint = $endpoint
        AdbServerPort = $adbServerPort
        AdbExecutable = $adbExecutable
        PackageName = $PackageName
        OutputDirectory = $outputStorageRoot
        DeviceChecks = 'Deferred until a smoke run'
    }
    return
}

$pages = [ordered]@{
    review = [pscustomobject]@{ NavigationId = 'nav_review'; RootId = 'root_review' }
    tutor = [pscustomobject]@{ NavigationId = 'nav_tutor'; RootId = 'root_tutor' }
    library = [pscustomobject]@{ NavigationId = 'nav_library'; RootId = 'root_library' }
    profile = [pscustomobject]@{ NavigationId = 'nav_profile'; RootId = 'root_profile' }
}
$observedAppProcessIds = [Collections.Generic.HashSet[int]]::new()

function Invoke-DedicatedAdb {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,
        [switch]$WithoutSerial,
        [int[]]$AllowedExitCodes = @(0)
    )

    if ([int]$env:ANDROID_ADB_SERVER_PORT -ne 5038) {
        throw 'ANDROID_ADB_SERVER_PORT changed during smoke QA.'
    }
    if (-not $WithoutSerial -and
        -not [string]::Equals($env:ANDROID_SERIAL, $endpoint, [StringComparison]::Ordinal)) {
        throw "ANDROID_SERIAL must remain pinned to $endpoint."
    }

    $adbArguments = @('-P', [string]$adbServerPort)
    if (-not $WithoutSerial) {
        $adbArguments += @('-s', $endpoint)
    }
    $adbArguments += $Arguments

    $output = @(& $adbExecutable @adbArguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($exitCode -notin $AllowedExitCodes) {
        $details = if ($output) { $output -join ' ' } else { '<no output>' }
        throw "adb $($adbArguments -join ' ') failed with exit code ${exitCode}: $details"
    }

    $output
}

function Convert-ToRuntimeWorkspacePath {
    param([Parameter(Mandatory)][string]$StoragePath)

    $fullStoragePath = [IO.Path]::GetFullPath($StoragePath)
    Assert-WorkspaceStoragePath -Path $fullStoragePath
    $relativePath = [IO.Path]::GetRelativePath($workspaceRoot, $fullStoragePath)
    Join-Path $runtimeWorkspaceRoot $relativePath
}

function Assert-TargetEmulatorListener {
    $emulatorAdbPort = Get-AndroidEmulatorAdbPort -Port $EmulatorPort
    $listener = Get-NetTCPConnection -State Listen -LocalPort $emulatorAdbPort -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) {
        throw "No Android emulator is listening on ADB port $emulatorAdbPort."
    }

    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    $runtimeEmulatorRoot = (Join-Path $env:ANDROID_HOME 'emulator').TrimEnd('\') + '\'
    $storageEmulatorRoot = (Join-Path $workspaceRoot '.toolchains\android-sdk\emulator').TrimEnd('\') + '\'
    $isWorkspaceEmulator = $listenerProcess -and $listenerProcess.ExecutablePath -and
        (
            $listenerProcess.ExecutablePath.StartsWith(
                $runtimeEmulatorRoot,
                [StringComparison]::OrdinalIgnoreCase
            ) -or
            $listenerProcess.ExecutablePath.StartsWith(
                $storageEmulatorRoot,
                [StringComparison]::OrdinalIgnoreCase
            )
        )
    $portPattern = "-port\s+$EmulatorPort(?:\s|$)"
    if (-not $isWorkspaceEmulator -or $listenerProcess.CommandLine -notmatch $portPattern) {
        $processPath = if ($listenerProcess) { $listenerProcess.ExecutablePath } else { '<unknown>' }
        throw "ADB port $emulatorAdbPort belongs to an unexpected process: $processPath"
    }
}

function Wait-ForTargetEmulator {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $state = ''
    $bootCompleted = ''
    do {
        $stateOutput = @(Invoke-DedicatedAdb -Arguments @('get-state') -AllowedExitCodes @(0, 1))
        $state = if ($stateOutput) { $stateOutput[-1].Trim() } else { '' }
        if ($state -eq 'device') {
            $bootOutput = @(Invoke-DedicatedAdb -Arguments @('shell', 'getprop', 'sys.boot_completed'))
            $bootCompleted = if ($bootOutput) { $bootOutput[-1].Trim() } else { '' }
            if ($bootCompleted -eq '1') {
                break
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($state -ne 'device' -or $bootCompleted -ne '1') {
        throw "Emulator endpoint $endpoint did not become ready within 60 seconds."
    }

    $qemuOutput = @(Invoke-DedicatedAdb -Arguments @('shell', 'getprop', 'ro.kernel.qemu'))
    $avdOutput = @(Invoke-DedicatedAdb -Arguments @('shell', 'getprop', 'ro.boot.qemu.avd_name'))
    $apiOutput = @(Invoke-DedicatedAdb -Arguments @('shell', 'getprop', 'ro.build.version.sdk'))
    $qemu = if ($qemuOutput) { $qemuOutput[-1].Trim() } else { '' }
    $avd = if ($avdOutput) { $avdOutput[-1].Trim() } else { '' }
    $api = if ($apiOutput) { $apiOutput[-1].Trim() } else { '' }
    if ($qemu -ne '1' -or $avd -ne $androidAvdName -or $api -ne [string]$androidRuntimeApi) {
        throw "Unexpected Android target at ${endpoint}: qemu=$qemu, avd=$avd, api=$api."
    }
}

function Get-AppProcessIds {
    $pidOutput = @(Invoke-DedicatedAdb `
        -Arguments @('shell', 'pidof', $PackageName) `
        -AllowedExitCodes @(0, 1))
    $pidText = ($pidOutput -join ' ').Trim()
    if (-not $pidText) {
        return @()
    }
    if ($pidText -notmatch '^\d+(?:\s+\d+)*$') {
        throw "Unexpected pidof output for ${PackageName}: $pidText"
    }

    @($pidText -split '\s+' | ForEach-Object { [int]$_ } | Sort-Object -Unique)
}

function Assert-NoPackageFailure {
    $systemLog = @(Invoke-DedicatedAdb -Arguments @('logcat', '-b', 'all', '-v', 'brief', '-d'))
    $escapedPackageName = [regex]::Escape($PackageName)
    $failureEventPattern = '(?i)\b(?:am_anr|am_crash)\b|ANR in|Application Not Responding|' +
        'Force finishing activity|has died|Fatal signal'
    $failureLines = @($systemLog | Where-Object {
        $_ -match $escapedPackageName -and $_ -match $failureEventPattern
    })
    if ($failureLines) {
        throw "Crash/ANR detected for ${PackageName}: $($failureLines[-1])"
    }
}

function Assert-AppHealthy {
    param([Parameter(Mandatory)][int]$ExpectedProcessId)

    Assert-NoPackageFailure
    $currentProcessIds = @(Get-AppProcessIds)
    foreach ($currentProcessId in $currentProcessIds) {
        [void]$observedAppProcessIds.Add($currentProcessId)
    }
    if ($ExpectedProcessId -notin $currentProcessIds) {
        $currentDescription = if ($currentProcessIds) { $currentProcessIds -join ', ' } else { '<none>' }
        throw "App process $ExpectedProcessId disappeared; current PIDs: $currentDescription."
    }

    $appLog = @(Invoke-DedicatedAdb -Arguments @(
        'logcat', '-b', 'all', '-v', 'brief', '-d', "--pid=$ExpectedProcessId"
    ))
    $fatalLines = @($appLog | Where-Object {
        $_ -match '(?i)FATAL EXCEPTION|Fatal signal\s+\d+|\bam_anr\b|\bANR\b'
    })
    if ($fatalLines) {
        throw "Crash/ANR detected in app PID ${ExpectedProcessId}: $($fatalLines[-1])"
    }
}

function Wait-ForAppProcess {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Assert-NoPackageFailure
        $processIds = @(Get-AppProcessIds)
        if ($processIds.Count -gt 1) {
            throw "Expected one main app process for $PackageName, found $($processIds -join ', ')."
        }
        if ($processIds.Count -eq 1) {
            [void]$observedAppProcessIds.Add($processIds[0])
            return $processIds[0]
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "App process for $PackageName did not start within 30 seconds."
}

function Read-UiHierarchy {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "UI hierarchy was not pulled to $Path."
    }
    if ((Get-Item -LiteralPath $Path).Length -eq 0) {
        throw "UI hierarchy is empty: $Path"
    }
    try {
        $document = [xml][IO.File]::ReadAllText($Path)
    } catch {
        throw "Invalid UI hierarchy XML at ${Path}: $($_.Exception.Message)"
    }

    return ,$document
}

function Get-ResourceNodes {
    param(
        [Parameter(Mandatory)][xml]$Document,
        [Parameter(Mandatory)][string]$ResourceId
    )

    @($Document.SelectNodes('//*[@resource-id]') | Where-Object {
        $actualId = $_.GetAttribute('resource-id')
        [string]::Equals($actualId, $ResourceId, [StringComparison]::Ordinal) -or
            $actualId.EndsWith(":id/$ResourceId", [StringComparison]::Ordinal)
    })
}

function Get-RequiredResourceNode {
    param(
        [Parameter(Mandatory)][xml]$Document,
        [Parameter(Mandatory)][string]$ResourceId
    )

    $nodes = @(Get-ResourceNodes -Document $Document -ResourceId $ResourceId)
    if ($nodes.Count -ne 1) {
        throw "Expected exactly one UI element with resource-id $ResourceId, found $($nodes.Count)."
    }

    $nodes[0]
}

function Get-BoundsCenter {
    param(
        [Parameter(Mandatory)]$Node,
        [Parameter(Mandatory)][string]$ResourceId
    )

    $bounds = $Node.GetAttribute('bounds')
    $match = [regex]::Match($bounds, '^\[(?<x1>-?\d+),(?<y1>-?\d+)\]\[(?<x2>-?\d+),(?<y2>-?\d+)\]$')
    if (-not $match.Success) {
        throw "Element $ResourceId has invalid bounds: $bounds"
    }

    $x1 = [int]$match.Groups['x1'].Value
    $y1 = [int]$match.Groups['y1'].Value
    $x2 = [int]$match.Groups['x2'].Value
    $y2 = [int]$match.Groups['y2'].Value
    if ($x1 -lt 0 -or $y1 -lt 0 -or $x2 -le $x1 -or $y2 -le $y1) {
        throw "Element $ResourceId has unusable bounds: $bounds"
    }

    [pscustomobject]@{
        X = [int][Math]::Floor(($x1 + $x2) / 2)
        Y = [int][Math]::Floor(($y1 + $y2) / 2)
    }
}

function Write-UiHierarchy {
    param(
        [Parameter(Mandatory)][string]$DevicePath,
        [Parameter(Mandatory)][string]$StoragePath
    )

    Assert-WorkspaceStoragePath -Path $StoragePath
    $runtimePath = Convert-ToRuntimeWorkspacePath -StoragePath $StoragePath
    $lastDumpOutput = @()
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        $null = Invoke-DedicatedAdb -Arguments @('shell', 'rm', '-f', $DevicePath)
        $lastDumpOutput = @(Invoke-DedicatedAdb `
            -Arguments @('shell', 'uiautomator', 'dump', '--compressed', $DevicePath) `
            -AllowedExitCodes @(0, 1))
        $remoteSizeOutput = @(Invoke-DedicatedAdb `
            -Arguments @('shell', 'stat', '-c', '%s', $DevicePath) `
            -AllowedExitCodes @(0, 1))
        $remoteSizeText = ($remoteSizeOutput -join '').Trim()
        if ($remoteSizeText -match '^\d+$' -and [long]$remoteSizeText -gt 0) {
            if (Test-Path -LiteralPath $StoragePath) {
                [IO.File]::Delete($StoragePath)
            }
            $null = Invoke-DedicatedAdb -Arguments @('pull', $DevicePath, $runtimePath)
            return Read-UiHierarchy -Path $StoragePath
        }
        if ($attempt -lt 6) {
            Start-Sleep -Milliseconds 500
        }
    }

    $details = if ($lastDumpOutput) { $lastDumpOutput -join ' ' } else { '<no output>' }
    throw "UI hierarchy was not created at $DevicePath after 6 attempts: $details"
}

function Wait-ForCurrentPage {
    param(
        [Parameter(Mandatory)][string]$DevicePath,
        [Parameter(Mandatory)][string]$StoragePath,
        [Parameter(Mandatory)][int]$AppProcessId
    )

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        Assert-AppHealthy -ExpectedProcessId $AppProcessId
        $document = Write-UiHierarchy -DevicePath $DevicePath -StoragePath $StoragePath
        $visiblePages = [Collections.Generic.List[string]]::new()
        foreach ($pageName in $pages.Keys) {
            $rootId = $pages[$pageName].RootId
            $rootNodes = @(Get-ResourceNodes -Document $document -ResourceId $rootId)
            if ($rootNodes.Count -gt 1) {
                throw "Expected at most one UI element with resource-id $rootId, found $($rootNodes.Count)."
            }
            if ($rootNodes.Count -eq 1) {
                $visiblePages.Add($pageName)
            }
        }

        if ($visiblePages.Count -gt 1) {
            throw "Multiple page roots are visible: $($visiblePages -join ', ')."
        }
        if ($visiblePages.Count -eq 1) {
            return $visiblePages[0]
        }
        if ($attempt -lt 30) {
            Start-Sleep -Milliseconds 500
        }
    }

    throw "No expected page root appeared after launching $PackageName."
}

function Wait-ForPageRoot {
    param(
        [Parameter(Mandatory)][string]$RootId,
        [Parameter(Mandatory)][string]$DevicePath,
        [Parameter(Mandatory)][string]$StoragePath,
        [Parameter(Mandatory)][int]$AppProcessId
    )

    for ($attempt = 1; $attempt -le 20; $attempt++) {
        Assert-AppHealthy -ExpectedProcessId $AppProcessId
        $document = Write-UiHierarchy -DevicePath $DevicePath -StoragePath $StoragePath
        $rootNodes = @(Get-ResourceNodes -Document $document -ResourceId $RootId)
        if ($rootNodes.Count -gt 1) {
            throw "Expected exactly one UI element with resource-id $RootId, found $($rootNodes.Count)."
        }
        if ($rootNodes.Count -eq 1) {
            return ,$document
        }
        if ($attempt -lt 20) {
            Start-Sleep -Milliseconds 350
        }
    }

    throw "Missing UI element with resource-id $RootId after navigation."
}

function Save-PageScreenshot {
    param(
        [Parameter(Mandatory)][string]$DevicePath,
        [Parameter(Mandatory)][string]$StoragePath,
        [Parameter(Mandatory)][int]$AppProcessId
    )

    Assert-AppHealthy -ExpectedProcessId $AppProcessId
    Assert-WorkspaceStoragePath -Path $StoragePath
    $runtimePath = Convert-ToRuntimeWorkspacePath -StoragePath $StoragePath
    $null = Invoke-DedicatedAdb -Arguments @('shell', 'rm', '-f', $DevicePath)
    $null = Invoke-DedicatedAdb -Arguments @('shell', 'screencap', '-p', $DevicePath)
    $null = Invoke-DedicatedAdb -Arguments @('pull', $DevicePath, $runtimePath)
    Assert-AppHealthy -ExpectedProcessId $AppProcessId

    $stream = [IO.File]::OpenRead($StoragePath)
    try {
        $signature = [byte[]]::new(8)
        $expectedSignature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
        $signatureMatches = $stream.Read($signature, 0, $signature.Length) -eq $signature.Length
        for ($index = 0; $signatureMatches -and $index -lt $signature.Length; $index++) {
            $signatureMatches = $signature[$index] -eq $expectedSignature[$index]
        }
        if (-not $signatureMatches) {
            throw "Screenshot is not a readable PNG: $StoragePath"
        }
    } finally {
        $stream.Dispose()
    }
}

function Save-AppLogcat {
    param([Parameter(Mandatory)][string]$StoragePath)

    Assert-WorkspaceStoragePath -Path $StoragePath
    $lines = [Collections.Generic.List[string]]::new()
    $processIds = @($observedAppProcessIds | Sort-Object)
    if (-not $processIds) {
        $lines.Add('No app PID was observed.')
    }
    foreach ($processId in $processIds) {
        $lines.Add("===== $PackageName PID $processId =====")
        $processLog = @(Invoke-DedicatedAdb -Arguments @(
            'logcat', '-b', 'all', '-v', 'threadtime', '-d', "--pid=$processId"
        ))
        foreach ($line in $processLog) {
            $lines.Add($line)
        }
    }

    [IO.File]::WriteAllLines($StoragePath, $lines, [Text.UTF8Encoding]::new($false))
}

$runId = '{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'),
    [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runStorageDirectory = Join-Path $outputStorageRoot $runId
$deviceRunDirectory = "/data/local/tmp/smart-mistake-book-smoke-$runId"
$priorAndroidSerialExists = Test-Path Env:ANDROID_SERIAL
$priorAndroidSerial = $env:ANDROID_SERIAL
$adbServerTouched = $false
$adbConnected = $false
$deviceRunDirectoryCreated = $false
$operationError = $null
$cleanupFailures = [Collections.Generic.List[string]]::new()
$result = $null

try {
    if (Test-Path -LiteralPath $outputStorageRoot) {
        if (-not (Test-Path -LiteralPath $outputStorageRoot -PathType Container)) {
            throw "Output path is not a directory: $outputStorageRoot"
        }
    } else {
        New-Item -ItemType Directory -Path $outputStorageRoot | Out-Null
    }
    Assert-WorkspaceStoragePath -Path $outputStorageRoot
    if (Test-Path -LiteralPath $runStorageDirectory) {
        throw "Refusing to overwrite existing smoke output: $runStorageDirectory"
    }
    New-Item -ItemType Directory -Path $runStorageDirectory | Out-Null
    Assert-WorkspaceStoragePath -Path $runStorageDirectory

    Assert-NoConflictingAndroidProcesses -ExpectedEmulatorPort $EmulatorPort
    Assert-TargetEmulatorListener
    Assert-WorkspaceAdbServerPortAvailable

    $env:ANDROID_SERIAL = $endpoint
    $adbServerTouched = $true
    $null = Invoke-DedicatedAdb -WithoutSerial -Arguments @('start-server')
    Assert-WorkspaceAdbServerOwnership

    $connectOutput = @(Invoke-DedicatedAdb -WithoutSerial -Arguments @('connect', $endpoint))
    if (($connectOutput -join "`n") -notmatch '(?i)(already )?connected to') {
        throw "adb connect did not confirm $endpoint`: $($connectOutput -join ' ')"
    }
    $adbConnected = $true
    Wait-ForTargetEmulator

    $packagePaths = @(Invoke-DedicatedAdb -Arguments @('shell', 'pm', 'path', $PackageName))
    if (-not @($packagePaths | Where-Object { $_ -match '^package:' })) {
        throw "Package $PackageName is not installed on $endpoint."
    }

    $resolveOutput = @(Invoke-DedicatedAdb -Arguments @(
        'shell', 'cmd', 'package', 'resolve-activity', '--brief',
        '-a', 'android.intent.action.MAIN',
        '-c', 'android.intent.category.LAUNCHER',
        $PackageName
    ))
    $componentPattern = '(?i)^' + [regex]::Escape($PackageName) + '/\S+$'
    $launchComponents = @($resolveOutput | ForEach-Object { $_.Trim() } | Where-Object {
        $_ -match $componentPattern
    } | Sort-Object -Unique)
    if ($launchComponents.Count -ne 1) {
        throw "Expected one launcher activity for $PackageName, found $($launchComponents.Count)."
    }
    $launchComponent = $launchComponents[0]

    $null = Invoke-DedicatedAdb -Arguments @('shell', 'am', 'force-stop', $PackageName)
    if ($ResetData) {
        $clearOutput = @(Invoke-DedicatedAdb -Arguments @('shell', 'pm', 'clear', $PackageName))
        if (($clearOutput -join "`n") -notmatch '(?m)^Success\s*$') {
            throw "pm clear did not report success for ${PackageName}: $($clearOutput -join ' ')"
        }
    }
    $null = Invoke-DedicatedAdb -Arguments @('logcat', '-b', 'all', '-c')

    $launchOutput = @(Invoke-DedicatedAdb -Arguments @(
        'shell', 'am', 'start', '-W', '-n', $launchComponent
    ))
    $launchStatus = $launchOutput -join "`n"
    if ($launchStatus -notmatch '(?m)^Status:\s*(?:ok|timeout)\s*$') {
        throw "App launch did not report success: $($launchOutput -join ' ')"
    }

    $appProcessId = Wait-ForAppProcess
    $null = Invoke-DedicatedAdb -Arguments @('shell', 'mkdir', '-p', $deviceRunDirectory)
    $deviceRunDirectoryCreated = $true

    $initialXmlStoragePath = Join-Path $runStorageDirectory 'initial.xml'
    $initialPage = Wait-ForCurrentPage `
        -DevicePath "$deviceRunDirectory/initial.xml" `
        -StoragePath $initialXmlStoragePath `
        -AppProcessId $appProcessId
    $navigationOrder = @($pages.Keys | Where-Object { $_ -ne $initialPage }) + @($initialPage)

    foreach ($pageName in $navigationOrder) {
        $page = $pages[$pageName]
        $pageXmlStoragePath = Join-Path $runStorageDirectory "$pageName.xml"
        $pagePngStoragePath = Join-Path $runStorageDirectory "$pageName.png"
        $pageXmlDevicePath = "$deviceRunDirectory/$pageName.xml"
        $pagePngDevicePath = "$deviceRunDirectory/$pageName.png"

        Assert-AppHealthy -ExpectedProcessId $appProcessId
        $navigationDocument = Write-UiHierarchy `
            -DevicePath $pageXmlDevicePath `
            -StoragePath $pageXmlStoragePath
        $navigationNode = Get-RequiredResourceNode `
            -Document $navigationDocument `
            -ResourceId $page.NavigationId
        if ($navigationNode.GetAttribute('clickable') -ne 'true' -or
            $navigationNode.GetAttribute('enabled') -ne 'true') {
            throw "Navigation element $($page.NavigationId) is not clickable and enabled."
        }
        $tap = Get-BoundsCenter -Node $navigationNode -ResourceId $page.NavigationId
        $null = Invoke-DedicatedAdb -Arguments @(
            'shell', 'input', 'tap', [string]$tap.X, [string]$tap.Y
        )
        Assert-AppHealthy -ExpectedProcessId $appProcessId

        $pageDocument = Wait-ForPageRoot `
            -RootId $page.RootId `
            -DevicePath $pageXmlDevicePath `
            -StoragePath $pageXmlStoragePath `
            -AppProcessId $appProcessId
        $null = Get-RequiredResourceNode -Document $pageDocument -ResourceId $page.RootId
        Save-PageScreenshot `
            -DevicePath $pagePngDevicePath `
            -StoragePath $pagePngStoragePath `
            -AppProcessId $appProcessId
    }

    Assert-AppHealthy -ExpectedProcessId $appProcessId
    $result = [pscustomobject]@{
        Status = 'Passed'
        Endpoint = $endpoint
        PackageName = $PackageName
        InitialPage = $initialPage
        PagesVerified = @($navigationOrder)
        OutputDirectory = $runStorageDirectory
        ResetData = [bool]$ResetData
    }
} catch {
    $operationError = $_
} finally {
    if ($adbConnected -and (Test-Path -LiteralPath $runStorageDirectory -PathType Container)) {
        try {
            Save-AppLogcat -StoragePath (Join-Path $runStorageDirectory 'app-logcat.txt')
        } catch {
            $cleanupFailures.Add("Unable to collect app logcat: $($_.Exception.Message)")
        }
    }
    if ($adbConnected -and $deviceRunDirectoryCreated) {
        try {
            $null = Invoke-DedicatedAdb -Arguments @('shell', 'rm', '-rf', $deviceRunDirectory)
        } catch {
            $cleanupFailures.Add("Unable to remove device smoke artifacts: $($_.Exception.Message)")
        }
    }

    if ($priorAndroidSerialExists) {
        $env:ANDROID_SERIAL = $priorAndroidSerial
    } else {
        Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
    }

    try {
        if ($adbServerTouched) {
            Stop-WorkspaceAdbServer
        }
    } catch {
        $cleanupFailures.Add($_.Exception.Message)
    }
    try {
        Move-GeneratedAndroidProfileArtifactsToWorkspace `
            -Attempts 20 `
            -DelayMilliseconds 250 `
            -ExpectedEmulatorPort $EmulatorPort
    } catch {
        $cleanupFailures.Add($_.Exception.Message)
    }

    $outsideWorkspaceArtifacts = @(@($profileAndroidDirectory, $profileConsoleToken) | Where-Object {
        Test-Path -LiteralPath $_
    })
    if ($outsideWorkspaceArtifacts) {
        $cleanupFailures.Add(
            "Profile artifacts remain outside the workspace: $($outsideWorkspaceArtifacts -join ', ')"
        )
    }
}

if ($cleanupFailures) {
    $operationDetails = if ($operationError) {
        " Smoke failure: $($operationError.Exception.Message)"
    } else {
        ''
    }
    throw "Android smoke QA cleanup failed.$operationDetails Cleanup errors: $($cleanupFailures -join ' | ')"
}
if ($operationError) {
    throw $operationError
}

$result
