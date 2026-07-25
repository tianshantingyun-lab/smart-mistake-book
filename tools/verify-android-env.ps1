$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'android-env.ps1')

$requiredCommands = @('java', 'javac', 'gradle', 'sdkmanager', 'avdmanager', 'adb', 'emulator')
$expectedCommands = @{
    java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    gradle = Join-Path $gradleRoot 'bin\gradle.bat'
    sdkmanager = Join-Path $androidSdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
    avdmanager = Join-Path $androidSdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
    adb = Join-Path $androidSdkRoot 'platform-tools\adb.exe'
    emulator = Join-Path $androidSdkRoot 'emulator\emulator.exe'
}

foreach ($commandName in $requiredCommands) {
    $commands = @(Get-Command $commandName -CommandType Application -ErrorAction Stop)
    $matchingCommand = $commands | Where-Object {
        [string]::Equals($_.Source, $expectedCommands[$commandName], [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1
    if (-not $matchingCommand) {
        throw "$commandName resolved to $($commands.Source -join ', '), expected $($expectedCommands[$commandName])"
    }
}

function Assert-NativeCommandSucceeded([string]$commandName) {
    if ($LASTEXITCODE -ne 0) {
        throw "$commandName failed with exit code $LASTEXITCODE"
    }
}

try {
    Write-Output '=== Workspace runtime ==='
    Write-Output "Storage root: $workspaceRoot"
    Write-Output "ASCII runtime alias: $runtimeWorkspaceRoot"

    Write-Output '=== Java ==='
    & $expectedCommands.java -version
    Assert-NativeCommandSucceeded 'java -version'

    Write-Output '=== Gradle ==='
    & $expectedCommands.gradle --version
    Assert-NativeCommandSucceeded 'gradle --version'

    Write-Output '=== Android tools ==='
    & $expectedCommands.sdkmanager --version
    Assert-NativeCommandSucceeded 'sdkmanager --version'
    & $expectedCommands.adb version
    Assert-NativeCommandSucceeded 'adb version'
    & $expectedCommands.emulator -version
    Assert-NativeCommandSucceeded 'emulator -version'
    $accelerationOutput = @(& $expectedCommands.emulator -accel-check 2>&1)
    Assert-NativeCommandSucceeded 'emulator -accel-check'
    $accelerationOutput | Write-Output
    if (($accelerationOutput -join "`n") -notmatch 'installed and usable') {
        throw 'Android emulator hardware acceleration is not usable.'
    }

    Write-Output '=== Installed SDK packages ==='
    $installedPackagesOutput = @(& $expectedCommands.sdkmanager --list_installed 2>&1)
    Assert-NativeCommandSucceeded 'sdkmanager --list_installed'
    $installedPackagesOutput | Write-Output
    $installedPackagesText = $installedPackagesOutput -join "`n"
    function Test-InstalledSdkPackage([string]$packageName) {
        $escapedPackageName = [regex]::Escape($packageName)
        $revisionSuffix = if ($packageName -eq $androidCompilePlatformPackage) { '(?:\.0)?' } else { '' }
        $installedPackagesText -match "(?m)^\s*$escapedPackageName$revisionSuffix(?=\s+\|)"
    }
    $requiredPackages = @(
        $androidBuildToolsPackage,
        'platform-tools',
        'emulator',
        $androidCompilePlatformPackage,
        $androidRuntimeSystemImagePackage
    )
    $missingPackages = @($requiredPackages | Where-Object { -not (Test-InstalledSdkPackage $_) })
    if ($missingPackages) {
        throw "Missing Android SDK packages: $($missingPackages -join ', ')"
    }

    Write-Output '=== Android virtual devices ==='
    $avdOutput = @(& $expectedCommands.avdmanager list avd 2>&1)
    Assert-NativeCommandSucceeded 'avdmanager list avd'
    $avdOutput | Write-Output
    $avdText = $avdOutput -join "`n"
    $expectedAvdPath = Join-Path $env:ANDROID_AVD_HOME "$androidAvdName.avd"
    if ($avdText -notmatch "Name:\s+$([regex]::Escape($androidAvdName))" -or
        $avdText -notmatch [regex]::Escape($expectedAvdPath)) {
        throw "Expected API $androidRuntimeApi Android virtual device $androidAvdName was not found at $expectedAvdPath."
    }

    $avdConfigPath = Join-Path $expectedAvdPath 'config.ini'
    if (-not (Test-Path -LiteralPath $avdConfigPath)) {
        throw "Android virtual device config was not found: $avdConfigPath"
    }
    $avdConfig = Get-Content -LiteralPath $avdConfigPath
    $expectedTarget = "target=android-$androidRuntimeApi"
    $expectedImageDirectory = "system-images\android-$androidRuntimeApi\google_apis\x86_64"
    $configuredImageDirectory = @($avdConfig | Where-Object { $_ -like 'image.sysdir.1=*' } | Select-Object -First 1)
    if ($expectedTarget -notin $avdConfig -or
        -not $configuredImageDirectory -or
        $configuredImageDirectory[0].Substring('image.sysdir.1='.Length).Replace('/', '\').TrimEnd('\') -ne $expectedImageDirectory) {
        throw "Android virtual device $androidAvdName does not use the required API $androidRuntimeApi Google APIs x86_64 image."
    }
} finally {
    try {
        Stop-WorkspaceAdbServer
    } finally {
        Move-GeneratedAndroidProfileArtifactsToWorkspace `
            -Attempts 20 `
            -DelayMilliseconds 250 `
            -ExpectedEmulatorPort $androidDefaultEmulatorPort
    }
}
