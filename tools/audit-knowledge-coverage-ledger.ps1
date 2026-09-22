[CmdletBinding()]
param(
    [switch]$RequireComplete,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$LedgerPath,
    [string]$SourceRegisterPath
)

$ErrorActionPreference = 'Stop'
$currentBaselineId = 'moe-high-school-2017-2025'
$requiredSubjects = @(
    'CHINESE',
    'MATH',
    'ENGLISH',
    'POLITICS',
    'HISTORY',
    'GEOGRAPHY',
    'PHYSICS',
    'CHEMISTRY',
    'BIOLOGY'
)
$allowedMappingStates = @(
    'NOT_STARTED',
    'DRAFT_UNREVIEWED',
    'REVIEWED'
)
$allowedRequirementTypes = @(
    'REQUIRED',
    'SELECTIVE_REQUIRED',
    'ELECTIVE'
)
$slugPattern = '^[a-z0-9]+(?:-[a-z0-9]+)*$'

if ([string]::IsNullOrWhiteSpace($LedgerPath)) {
    $LedgerPath = Join-Path $ProjectRoot (
        'knowledge-production\knowledge-coverage-ledger-2025-v1.json'
    )
}
if ([string]::IsNullOrWhiteSpace($SourceRegisterPath)) {
    $SourceRegisterPath = Join-Path $ProjectRoot (
        'knowledge-production\source-register-2025-v1.json'
    )
}

function Assert-OnlyKeys {
    param(
        [object]$Value,
        [string[]]$Required,
        [string[]]$Optional = @(),
        [string]$Label
    )
    $actual = @($Value.PSObject.Properties.Name)
    $allowed = @($Required + $Optional)
    $unknown = @($actual | Where-Object { $_ -notin $allowed })
    $missing = @($Required | Where-Object { $_ -notin $actual })
    if ($unknown.Count -gt 0) {
        throw "$Label contains unknown keys: $($unknown -join ', ')"
    }
    if ($missing.Count -gt 0) {
        throw "$Label is missing keys: $($missing -join ', ')"
    }
}

function Assert-TrimmedText {
    param(
        [AllowNull()]
        [object]$Value,
        [string]$Label,
        [int]$MaximumLength = 4096
    )
    if (
        $null -eq $Value -or
        $Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace($Value) -or
        $Value -ne $Value.Trim() -or
        $Value.Length -gt $MaximumLength
    ) {
        throw "$Label must be a trimmed non-blank string of at most $MaximumLength characters"
    }
}

function Assert-Unique {
    param(
        [AllowEmptyCollection()]
        [AllowNull()]
        [object[]]$Values,
        [string]$Label
    )
    $items = @($Values | Where-Object { $null -ne $_ })
    if (@($items | Sort-Object -Unique).Count -ne $items.Count) {
        throw "$Label must be unique"
    }
}

if (-not (Test-Path -LiteralPath $LedgerPath -PathType Leaf)) {
    throw "Knowledge coverage ledger does not exist: $LedgerPath"
}
if (-not (Test-Path -LiteralPath $SourceRegisterPath -PathType Leaf)) {
    throw "Knowledge source register does not exist: $SourceRegisterPath"
}

$ledger = Get-Content -Raw -LiteralPath $LedgerPath | ConvertFrom-Json
$register = Get-Content -Raw -LiteralPath $SourceRegisterPath | ConvertFrom-Json
Assert-OnlyKeys `
    -Value $ledger `
    -Required @(
        'schemaVersion',
        'ledgerId',
        'targetBaselineId',
        'sourceRegisterId',
        'updatedAtEpochMillis',
        'subjects'
    ) `
    -Label 'Knowledge coverage ledger'

if ([int]$ledger.schemaVersion -ne 1) {
    throw "Unsupported knowledge coverage-ledger schema $($ledger.schemaVersion)"
}
Assert-TrimmedText -Value $ledger.ledgerId -Label 'ledgerId' -MaximumLength 256
if ([string]$ledger.targetBaselineId -ne $currentBaselineId) {
    throw "Knowledge coverage ledger must target $currentBaselineId"
}
if ([string]$ledger.sourceRegisterId -ne [string]$register.registerId) {
    throw 'Knowledge coverage ledger must reference the audited source register'
}
if ([long]$ledger.updatedAtEpochMillis -le 0) {
    throw 'updatedAtEpochMillis must be positive'
}
if ($ledger.subjects -isnot [System.Array]) {
    throw 'subjects must be a JSON array'
}

$subjects = @($ledger.subjects)
$declaredSubjects = @($subjects.subject)
Assert-Unique -Values $declaredSubjects -Label 'Coverage-ledger subjects'
if (@(Compare-Object $requiredSubjects $declaredSubjects).Count -ne 0) {
    throw 'Knowledge coverage ledger must declare each required subject exactly once'
}

$sourcesById = @{}
foreach ($source in @($register.sources)) {
    $sourcesById[[string]$source.sourceId] = $source
}

$allCandidatePointKeys = [System.Collections.Generic.List[string]]::new()
$reviewedPointKeys = [System.Collections.Generic.List[string]]::new()
$subjectReports = foreach ($subjectEntry in $subjects) {
    $subject = [string]$subjectEntry.subject
    $label = "subject '$subject'"
    Assert-OnlyKeys `
        -Value $subjectEntry `
        -Required @(
            'subject',
            'curriculumSourceId',
            'mappingState',
            'modules'
        ) `
        -Optional @('reviewedAtEpochMillis') `
        -Label $label
    if ($subject -notin $requiredSubjects) {
        throw "$label is not a required subject"
    }
    Assert-TrimmedText `
        -Value $subjectEntry.curriculumSourceId `
        -Label "$label.curriculumSourceId" `
        -MaximumLength 256
    if ($subjectEntry.mappingState -notin $allowedMappingStates) {
        throw "$label has an invalid mappingState"
    }
    if ($subjectEntry.modules -isnot [System.Array]) {
        throw "$label.modules must be a JSON array"
    }

    $source = $sourcesById[[string]$subjectEntry.curriculumSourceId]
    if ($null -eq $source) {
        throw "$label references an unknown curriculum source"
    }
    if (
        $source.baselineId -ne $currentBaselineId -or
        'CURRENT_CURRICULUM_TEXT' -notin @($source.purposes) -or
        @($source.subjects).Count -ne 1 -or
        $source.subjects[0] -ne $subject
    ) {
        throw "$label must reference its own current curriculum-text source"
    }

    $modules = @($subjectEntry.modules)
    if ($subjectEntry.mappingState -eq 'NOT_STARTED' -and $modules.Count -ne 0) {
        throw "$label cannot contain modules before mapping starts"
    }
    if ($subjectEntry.mappingState -eq 'REVIEWED') {
        if ($source.acquisitionState -ne 'ACQUIRED_REVIEWED') {
            throw "$label cannot be reviewed before its curriculum source is reviewed"
        }
        if ($modules.Count -eq 0) {
            throw "$label reviewed mapping must contain modules"
        }
        if (
            $null -eq $subjectEntry.reviewedAtEpochMillis -or
            [long]$subjectEntry.reviewedAtEpochMillis -le 0
        ) {
            throw "$label reviewed mapping needs a positive reviewedAtEpochMillis"
        }
        if ([long]$subjectEntry.reviewedAtEpochMillis -gt [long]$ledger.updatedAtEpochMillis) {
            throw "$label cannot be reviewed after the ledger update"
        }
    } elseif ($null -ne $subjectEntry.reviewedAtEpochMillis) {
        throw "$label may declare reviewedAtEpochMillis only after review"
    }

    $moduleSlugs = @($modules.slug)
    Assert-Unique -Values $moduleSlugs -Label "$label module slugs"
    $pointCount = 0
    $subjectPointSlugs = [System.Collections.Generic.List[string]]::new()
    foreach ($module in $modules) {
        $moduleLabel = "$label module '$($module.slug)'"
        Assert-OnlyKeys `
            -Value $module `
            -Required @(
                'slug',
                'name',
                'requirementType',
                'sourceLocator',
                'knowledgePoints'
            ) `
            -Optional @('courseStages') `
            -Label $moduleLabel
        Assert-TrimmedText -Value $module.slug -Label "$moduleLabel.slug" -MaximumLength 120
        if ($module.slug -notmatch $slugPattern) {
            throw "$moduleLabel.slug must be a lowercase kebab-case identifier"
        }
        Assert-TrimmedText -Value $module.name -Label "$moduleLabel.name" -MaximumLength 240
        Assert-TrimmedText `
            -Value $module.sourceLocator `
            -Label "$moduleLabel.sourceLocator" `
            -MaximumLength 2000
        if ($module.requirementType -notin $allowedRequirementTypes) {
            throw "$moduleLabel has an invalid requirementType"
        }
        $courseStages = if ($null -eq $module.courseStages) {
            @([string]$module.requirementType)
        } else {
            if ($module.courseStages -isnot [System.Array]) {
                throw "$moduleLabel.courseStages must be a JSON array"
            }
            @($module.courseStages)
        }
        if (
            $courseStages.Count -eq 0 -or
            @(
                $courseStages |
                    Where-Object { $_ -notin $allowedRequirementTypes }
            ).Count -gt 0
        ) {
            throw "$moduleLabel.courseStages must contain only allowed course stages"
        }
        Assert-Unique -Values $courseStages -Label "$moduleLabel course stages"
        if ($module.requirementType -notin $courseStages) {
            throw "$moduleLabel.requirementType must be included in courseStages"
        }
        if ($module.knowledgePoints -isnot [System.Array]) {
            throw "$moduleLabel.knowledgePoints must be a JSON array"
        }
        $points = @($module.knowledgePoints)
        if ($points.Count -eq 0) {
            throw "$moduleLabel must contain knowledge points"
        }
        Assert-Unique -Values @($points.slug) -Label "$moduleLabel knowledge-point slugs"
        foreach ($point in $points) {
            $pointLabel = "$moduleLabel point '$($point.slug)'"
            Assert-OnlyKeys `
                -Value $point `
                -Required @('slug', 'name', 'sourceLocator') `
                -Label $pointLabel
            Assert-TrimmedText -Value $point.slug -Label "$pointLabel.slug" -MaximumLength 120
            if ($point.slug -notmatch $slugPattern) {
                throw "$pointLabel.slug must be a lowercase kebab-case identifier"
            }
            Assert-TrimmedText -Value $point.name -Label "$pointLabel.name" -MaximumLength 240
            Assert-TrimmedText `
                -Value $point.sourceLocator `
                -Label "$pointLabel.sourceLocator" `
                -MaximumLength 2000
            $subjectPointSlugs.Add([string]$point.slug)
            $pointKey = "$subject/$($module.slug)/$($point.slug)"
            $allCandidatePointKeys.Add($pointKey)
            if ($subjectEntry.mappingState -eq 'REVIEWED') {
                $reviewedPointKeys.Add($pointKey)
            }
            $pointCount += 1
        }
    }
    Assert-Unique `
        -Values @($subjectPointSlugs) `
        -Label "$label knowledge-point slugs across modules"
    [pscustomobject]@{
        subject = $subject
        mappingState = [string]$subjectEntry.mappingState
        curriculumSourceState = [string]$source.acquisitionState
        moduleCount = $modules.Count
        candidatePointCount = $pointCount
        reviewedPointCount = if ($subjectEntry.mappingState -eq 'REVIEWED') {
            $pointCount
        } else {
            0
        }
    }
}

Assert-Unique `
    -Values @($allCandidatePointKeys) `
    -Label 'Coverage-ledger candidate knowledge-point keys'
Assert-Unique `
    -Values @($reviewedPointKeys) `
    -Label 'Coverage-ledger reviewed knowledge-point keys'
$reviewedSubjects = @(
    $subjectReports |
        Where-Object { $_.mappingState -eq 'REVIEWED' } |
        ForEach-Object { $_.subject }
)
$missingReviewedSubjects = @(
    $requiredSubjects | Where-Object { $_ -notin $reviewedSubjects }
)
$coverageLedgerReady = (
    $missingReviewedSubjects.Count -eq 0 -and
    $reviewedPointKeys.Count -gt 0
)

$report = [ordered]@{
    ledgerId = [string]$ledger.ledgerId
    targetBaselineId = [string]$ledger.targetBaselineId
    coverageLedgerReady = $coverageLedgerReady
    reviewedSubjects = $reviewedSubjects
    missingReviewedSubjects = $missingReviewedSubjects
    candidatePointCount = $allCandidatePointKeys.Count
    requiredPointCount = $reviewedPointKeys.Count
    requiredPointKeys = @($reviewedPointKeys)
    subjects = @($subjectReports)
}

$report | ConvertTo-Json -Depth 6

if ($RequireComplete -and -not $coverageLedgerReady) {
    throw (
        'The independent 2025 curriculum coverage ledger is incomplete. ' +
        'Missing reviewed subjects: ' +
        ($missingReviewedSubjects -join ', ')
    )
}
