[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$CatalogRoot,
    [long]$ExpectedModuleVersion = 987894174,
    [switch]$RequireNineSubjects
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($CatalogRoot)) {
    $CatalogRoot = Join-Path $ProjectRoot '.artifacts\research'
}
$CatalogRoot = (Resolve-Path -LiteralPath $CatalogRoot).Path

$versionPath = Join-Path $CatalogRoot 'smartedu-tch-material-version.json'
$partFiles = @(
    Get-ChildItem `
        -LiteralPath $CatalogRoot `
        -Filter 'smartedu-tch-material-part-*.json' `
        -File |
        Sort-Object Name
)

if (-not (Test-Path -LiteralPath $versionPath -PathType Leaf)) {
    throw "SmartEdu catalog version file is missing: $versionPath"
}
if ($partFiles.Count -eq 0) {
    throw "SmartEdu catalog part files are missing under: $CatalogRoot"
}

$versionRaw = Get-Content -LiteralPath $versionPath -Raw
$version = $versionRaw | ConvertFrom-Json
if ($version.module -ne 'tch_material') {
    throw 'SmartEdu catalog module must be tch_material'
}
if ([long]$version.module_version -le 0) {
    throw 'SmartEdu catalog module_version must be positive'
}
if (
    $ExpectedModuleVersion -gt 0 -and
    [long]$version.module_version -ne $ExpectedModuleVersion
) {
    throw (
        "SmartEdu catalog module_version changed: expected $ExpectedModuleVersion, " +
        "found $($version.module_version)"
    )
}

$declaredUrls = @(
    ([string]$version.urls).Split(
        ',',
        [System.StringSplitOptions]::RemoveEmptyEntries
    )
)
if ($declaredUrls.Count -ne $partFiles.Count) {
    throw (
        "SmartEdu catalog declares $($declaredUrls.Count) parts but " +
        "$($partFiles.Count) local parts were found"
    )
}

$resources = [System.Collections.Generic.List[object]]::new()
$partReports = [System.Collections.Generic.List[object]]::new()
foreach ($partFile in $partFiles) {
    $part = @(
        Get-Content -LiteralPath $partFile.FullName -Raw |
            ConvertFrom-Json
    )
    if ($part.Count -eq 0) {
        throw "SmartEdu catalog part is empty: $($partFile.Name)"
    }
    foreach ($resource in $part) {
        $resources.Add($resource)
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

$resourceIds = @($resources | ForEach-Object { [string]$_.id })
if (
    @($resourceIds | Sort-Object -Unique).Count -ne
    $resourceIds.Count
) {
    throw 'SmartEdu catalog resource ids must be unique'
}

$requiredSubjects = [ordered]@{
    CHINESE = '语文'
    MATH = '数学'
    ENGLISH = '英语'
    POLITICS = '思想政治'
    HISTORY = '历史'
    GEOGRAPHY = '地理'
    PHYSICS = '物理'
    CHEMISTRY = '化学'
    BIOLOGY = '生物学'
}

$subjectCoverage = [ordered]@{}
$missingSubjects = [System.Collections.Generic.List[string]]::new()
foreach ($entry in $requiredSubjects.GetEnumerator()) {
    $subjectResources = @(
        $resources |
            Where-Object {
                $_.status -eq 'ONLINE' -and
                $_.tag_list.tag_name -contains '高中' -and
                $_.tag_list.tag_name -contains $entry.Value
            }
    )
    $versions = @(
        $subjectResources |
            ForEach-Object {
                $_.tag_list |
                    Where-Object { $_.tag_dimension_id -eq 'zxxbb' } |
                    ForEach-Object { [string]$_.tag_name }
            } |
            Sort-Object -Unique
    )
    $subjectCoverage[$entry.Key] = [ordered]@{
        displayName = $entry.Value
        onlineTextbookRecords = $subjectResources.Count
        versions = $versions
    }
    if ($subjectResources.Count -eq 0) {
        $missingSubjects.Add($entry.Key)
    }
}

$versionFile = Get-Item -LiteralPath $versionPath
$report = [ordered]@{
    valid = $missingSubjects.Count -eq 0
    module = [string]$version.module
    moduleVersion = [long]$version.module_version
    totalResourceRecords = $resources.Count
    uniqueResourceIds = @($resourceIds | Sort-Object -Unique).Count
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
        'SmartEdu textbook catalog is missing high-school subjects: ' +
        (@($missingSubjects) -join ', ')
    )
}
