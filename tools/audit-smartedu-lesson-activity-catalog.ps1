[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$CatalogRoot,
    [long]$ExpectedModuleVersion = 344642008,
    [switch]$RequireNineSubjects
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($CatalogRoot)) {
    $CatalogRoot = Join-Path $ProjectRoot '.artifacts\research'
}
$CatalogRoot = (Resolve-Path -LiteralPath $CatalogRoot).Path

$versionPath = Join-Path $CatalogRoot 'smartedu-lesson-activity-version.json'
$partFiles = @(
    Get-ChildItem `
        -LiteralPath $CatalogRoot `
        -Filter 'smartedu-lesson-part_*.js' `
        -File |
        Sort-Object Name
)

if (-not (Test-Path -LiteralPath $versionPath -PathType Leaf)) {
    throw "SmartEdu lesson-activity catalog version file is missing: $versionPath"
}
if ($partFiles.Count -eq 0) {
    throw "SmartEdu lesson-activity catalog parts are missing under: $CatalogRoot"
}

$version = Get-Content -LiteralPath $versionPath -Raw | ConvertFrom-Json
if ($version.module -ne 'lesson_activity') {
    throw 'SmartEdu lesson-activity catalog module must be lesson_activity'
}
if ([long]$version.module_version -le 0) {
    throw 'SmartEdu lesson-activity catalog module_version must be positive'
}
if (
    $ExpectedModuleVersion -gt 0 -and
    [long]$version.module_version -ne $ExpectedModuleVersion
) {
    throw (
        "SmartEdu lesson-activity catalog module_version changed: expected " +
        "$ExpectedModuleVersion, found $($version.module_version)"
    )
}

$declaredUrls = @(
    ([string]$version.urls).Split(
        ',',
        [System.StringSplitOptions]::RemoveEmptyEntries
    )
)
$declaredPartNames = @(
    $declaredUrls |
        ForEach-Object {
            [System.IO.Path]::GetFileName(([System.Uri]$_).AbsolutePath)
        } |
        Sort-Object
)
$localPartNames = @(
    $partFiles.Name |
        ForEach-Object { $_ -replace '^smartedu-lesson-', '' } |
        Sort-Object
)
if (
    $declaredPartNames.Count -ne $localPartNames.Count -or
    (Compare-Object $declaredPartNames $localPartNames).Count -gt 0
) {
    throw 'SmartEdu lesson-activity catalog local parts do not match its version manifest'
}

function ConvertFrom-LessonActivityPart {
    param(
        [Parameter(Mandatory)]
        [System.IO.FileInfo]$PartFile
    )

    $raw = [System.IO.File]::ReadAllText($PartFile.FullName)
    $prefix = '.concat(['
    $start = $raw.IndexOf($prefix, [System.StringComparison]::Ordinal)
    $end = $raw.LastIndexOf('])', [System.StringComparison]::Ordinal)
    if ($start -lt 0 -or $end -le $start) {
        throw "Unexpected SmartEdu lesson-activity wrapper: $($PartFile.Name)"
    }

    $jsonStart = $start + $prefix.Length - 1
    $json = $raw.Substring($jsonStart, $end - $jsonStart + 1)
    return @($json | ConvertFrom-Json)
}

$activities = [System.Collections.Generic.List[object]]::new()
$partReports = [System.Collections.Generic.List[object]]::new()
foreach ($partFile in $partFiles) {
    $part = @(ConvertFrom-LessonActivityPart -PartFile $partFile)
    if ($part.Count -eq 0) {
        throw "SmartEdu lesson-activity catalog part is empty: $($partFile.Name)"
    }
    foreach ($activity in $part) {
        $activities.Add($activity)
    }
    $partReports.Add(
        [ordered]@{
            file = $partFile.Name
            records = $part.Count
            contentLengthBytes = $partFile.Length
            sha256 = (
                Get-FileHash -LiteralPath $partFile.FullName -Algorithm SHA256
            ).Hash
        }
    )
}

$activityIds = @($activities | ForEach-Object { [string]$_.activity_id })
if (
    @($activityIds | Sort-Object -Unique).Count -ne
    $activityIds.Count
) {
    throw 'SmartEdu lesson-activity catalog activity ids must be unique'
}

$requiredSubjects = [ordered]@{
    CHINESE = [ordered]@{ displayName = '语文'; tagCode = '$SB0100' }
    MATH = [ordered]@{ displayName = '数学'; tagCode = '$SB0200' }
    ENGLISH = [ordered]@{ displayName = '英语'; tagCode = '$SB0300' }
    PHYSICS = [ordered]@{ displayName = '物理'; tagCode = '$SB0400' }
    CHEMISTRY = [ordered]@{ displayName = '化学'; tagCode = '$SB0500' }
    BIOLOGY = [ordered]@{ displayName = '生物学'; tagCode = '$SB0600' }
    HISTORY = [ordered]@{ displayName = '历史'; tagCode = '$SB0900' }
    GEOGRAPHY = [ordered]@{ displayName = '地理'; tagCode = '$SB1000' }
    POLITICS = [ordered]@{ displayName = '思想政治'; tagCode = '$SB1300' }
}
$highSchoolTagCode = '$ON040000'
$highSchoolActivities = @(
    $activities |
        Where-Object { $_.tag_codes -contains $highSchoolTagCode }
)

$subjectCoverage = [ordered]@{}
$missingSubjects = [System.Collections.Generic.List[string]]::new()
foreach ($entry in $requiredSubjects.GetEnumerator()) {
    $subjectActivities = @(
        $highSchoolActivities |
            Where-Object {
                $_.tag_codes -contains $entry.Value.tagCode
            }
    )
    $subjectCoverage[$entry.Key] = [ordered]@{
        displayName = $entry.Value.displayName
        tagCode = $entry.Value.tagCode
        lessonActivityRecords = $subjectActivities.Count
        activitySetRecords = @(
            $subjectActivities.activity_set_id |
                Sort-Object -Unique
        ).Count
        sampleActivityNames = @(
            $subjectActivities |
                Select-Object -First 5 -ExpandProperty activity_name
        )
    }
    if ($subjectActivities.Count -eq 0) {
        $missingSubjects.Add($entry.Key)
    }
}

$versionFile = Get-Item -LiteralPath $versionPath
$report = [ordered]@{
    valid = $missingSubjects.Count -eq 0
    catalogEvidenceOnly = $true
    countsAsReviewedTeachingEvidence = $false
    module = [string]$version.module
    moduleVersion = [long]$version.module_version
    totalActivityRecords = $activities.Count
    uniqueActivityIds = @($activityIds | Sort-Object -Unique).Count
    highSchoolActivityRecords = $highSchoolActivities.Count
    declaredPartCount = $declaredUrls.Count
    localPartCount = $partFiles.Count
    versionFile = [ordered]@{
        file = $versionFile.Name
        contentLengthBytes = $versionFile.Length
        sha256 = (
            Get-FileHash -LiteralPath $versionFile.FullName -Algorithm SHA256
        ).Hash
    }
    parts = @($partReports)
    subjectCoverage = $subjectCoverage
    missingSubjects = @($missingSubjects)
}

$report | ConvertTo-Json -Depth 10

if ($RequireNineSubjects -and $missingSubjects.Count -gt 0) {
    throw (
        'SmartEdu lesson-activity catalog is missing high-school subjects: ' +
        (@($missingSubjects) -join ', ')
    )
}
