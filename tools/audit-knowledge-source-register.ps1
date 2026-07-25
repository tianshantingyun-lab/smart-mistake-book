[CmdletBinding()]
param(
    [switch]$RequireProductionReady,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$RegisterPath
)

$ErrorActionPreference = 'Stop'
$currentBaselineId = 'moe-high-school-2017-2025'
$historicalBaselineId = 'moe-high-school-2017-2020'
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
$allowedPurposes = @(
    'COVERAGE_BASELINE',
    'CURRENT_CURRICULUM_TEXT',
    'REVISION_DELTA',
    'REVISION_INTERPRETATION',
    'IMPLEMENTATION_NOTICE',
    'TEXTBOOK_CATALOG',
    'LESSON_ACTIVITY_CATALOG',
    'TEACHING_REFERENCE',
    'METHOD_REFERENCE',
    'WORKED_EXAMPLE_REFERENCE'
)
$allowedAuthorityLevels = @(
    'OFFICIAL',
    'AUTHORIZED_EDUCATION',
    'THIRD_PARTY_EDUCATION'
)
$allowedAcquisitionStates = @(
    'PENDING_ACQUISITION',
    'METADATA_VERIFIED',
    'ACQUIRED_UNREVIEWED',
    'ACQUIRED_REVIEWED',
    'REJECTED'
)
$allowedLicenseStatuses = @(
    'PUBLIC_OFFICIAL',
    'LICENSED',
    'REFERENCE_ONLY'
)
$allowedContentUsePolicies = @(
    'REVIEWED_SYNTHESIS_ONLY',
    'EXCERPT_ALLOWED',
    'ADAPTATION_ALLOWED'
)
$allowedModelUsePolicies = @(
    'DERIVED_CONTENT_ONLY',
    'REVIEWED_EXCERPT_ONLY',
    'FULL_CONTENT_ALLOWED'
)
$uppercaseSha256 = '^[A-F0-9]{64}$'

if ([string]::IsNullOrWhiteSpace($RegisterPath)) {
    $RegisterPath = Join-Path $ProjectRoot (
        'core\data\src\main\resources\knowledge\source-register-2025-v1.json'
    )
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

function Assert-HttpsUri {
    param(
        [AllowNull()]
        [object]$Value,
        [string]$Label
    )
    Assert-TrimmedText -Value $Value -Label $Label -MaximumLength 2048
    $parsed = $null
    if (
        -not [Uri]::TryCreate([string]$Value, [UriKind]::Absolute, [ref]$parsed) -or
        $parsed.Scheme -ne 'https'
    ) {
        throw "$Label must be an absolute HTTPS URI"
    }
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

function Test-HasPurpose {
    param(
        [object]$Source,
        [string]$Purpose
    )
    return $Purpose -in @($Source.purposes)
}

if (-not (Test-Path -LiteralPath $RegisterPath -PathType Leaf)) {
    throw "Knowledge source register does not exist: $RegisterPath"
}

$manifest = Get-Content -Raw -LiteralPath $RegisterPath | ConvertFrom-Json
Assert-OnlyKeys `
    -Value $manifest `
    -Required @(
        'schemaVersion',
        'registerId',
        'targetBaselineId',
        'reviewedAtEpochMillis',
        'requiredSubjects',
        'sourceRequirements',
        'sources'
    ) `
    -Label 'Knowledge source register'

if ([int]$manifest.schemaVersion -ne 1) {
    throw "Unsupported knowledge source-register schema $($manifest.schemaVersion)"
}
Assert-TrimmedText -Value $manifest.registerId -Label 'registerId' -MaximumLength 256
if ([string]$manifest.targetBaselineId -ne $currentBaselineId) {
    throw "Knowledge source register must target $currentBaselineId"
}
if ([long]$manifest.reviewedAtEpochMillis -le 0) {
    throw 'reviewedAtEpochMillis must be positive'
}

if ($manifest.requiredSubjects -isnot [System.Array]) {
    throw 'requiredSubjects must be a JSON array'
}
$declaredSubjects = @($manifest.requiredSubjects)
if (
    @($declaredSubjects | Sort-Object -Unique).Count -ne $declaredSubjects.Count -or
    @(Compare-Object $requiredSubjects $declaredSubjects).Count -ne 0
) {
    throw 'Knowledge source register must declare each of the nine required subjects exactly once'
}

Assert-OnlyKeys `
    -Value $manifest.sourceRequirements `
    -Required @(
        'minimumReviewedTeachingReferencesPerSubject',
        'requireReviewedMethodReferencePerSubject',
        'requireReviewedWorkedExampleReferencePerSubject'
    ) `
    -Label 'sourceRequirements'

$minimumTeachingReferences = [int](
    $manifest.sourceRequirements.minimumReviewedTeachingReferencesPerSubject
)
if ($minimumTeachingReferences -lt 2 -or $minimumTeachingReferences -gt 10) {
    throw 'minimumReviewedTeachingReferencesPerSubject must be between 2 and 10'
}
if (
    $manifest.sourceRequirements.requireReviewedMethodReferencePerSubject -isnot [bool] -or
    -not $manifest.sourceRequirements.requireReviewedMethodReferencePerSubject
) {
    throw 'Every subject must require a reviewed method reference'
}
if (
    $manifest.sourceRequirements.requireReviewedWorkedExampleReferencePerSubject -isnot [bool] -or
    -not $manifest.sourceRequirements.requireReviewedWorkedExampleReferencePerSubject
) {
    throw 'Every subject must require a reviewed worked-example reference'
}

if ($manifest.sources -isnot [System.Array]) {
    throw 'sources must be a JSON array'
}
$sources = @($manifest.sources)
if ($sources.Count -eq 0) {
    throw 'Knowledge source register must contain source records'
}
$sourceIds = @($sources.sourceId)
if (@($sourceIds | Sort-Object -Unique).Count -ne $sourceIds.Count) {
    throw 'Knowledge source-register ids must be unique'
}

$sourceRequiredKeys = @(
    'sourceId',
    'title',
    'publisher',
    'baselineId',
    'subjects',
    'purposes',
    'authorityLevel',
    'acquisitionState',
    'discoveryUri',
    'sourceLocator',
    'licenseStatus',
    'contentUsePolicy',
    'modelUsePolicy',
    'reviewedAtEpochMillis',
    'independenceGroup'
)
$sourceOptionalKeys = @(
    'documentUri',
    'contentLengthBytes',
    'contentFingerprint',
    'licenseExpression',
    'licenseUri',
    'attributionText'
)

foreach ($source in $sources) {
    $label = "source '$($source.sourceId)'"
    Assert-OnlyKeys `
        -Value $source `
        -Required $sourceRequiredKeys `
        -Optional $sourceOptionalKeys `
        -Label $label
    Assert-TrimmedText -Value $source.sourceId -Label "$label.sourceId" -MaximumLength 256
    Assert-TrimmedText -Value $source.title -Label "$label.title" -MaximumLength 512
    Assert-TrimmedText -Value $source.publisher -Label "$label.publisher" -MaximumLength 256
    Assert-TrimmedText -Value $source.baselineId -Label "$label.baselineId" -MaximumLength 256
    Assert-TrimmedText `
        -Value $source.sourceLocator `
        -Label "$label.sourceLocator" `
        -MaximumLength 4096
    Assert-TrimmedText `
        -Value $source.independenceGroup `
        -Label "$label.independenceGroup" `
        -MaximumLength 256
    Assert-HttpsUri -Value $source.discoveryUri -Label "$label.discoveryUri"
    if ($null -ne $source.documentUri) {
        Assert-HttpsUri -Value $source.documentUri -Label "$label.documentUri"
    }
    if ($null -ne $source.contentLengthBytes -and [long]$source.contentLengthBytes -le 0) {
        throw "$label.contentLengthBytes must be positive"
    }

    if ($source.subjects -isnot [System.Array]) {
        throw "$label.subjects must be a JSON array"
    }
    $subjects = @($source.subjects)
    if (
        $subjects.Count -eq 0 -or
        @($subjects | Sort-Object -Unique).Count -ne $subjects.Count -or
        @($subjects | Where-Object { $_ -notin $requiredSubjects }).Count -gt 0
    ) {
        throw "$label must reference one or more unique required subjects"
    }
    if ($source.purposes -isnot [System.Array]) {
        throw "$label.purposes must be a JSON array"
    }
    $purposes = @($source.purposes)
    if (
        $purposes.Count -eq 0 -or
        @($purposes | Sort-Object -Unique).Count -ne $purposes.Count -or
        @($purposes | Where-Object { $_ -notin $allowedPurposes }).Count -gt 0
    ) {
        throw "$label contains an invalid or duplicate purpose"
    }
    if ($source.authorityLevel -notin $allowedAuthorityLevels) {
        throw "$label has an invalid authorityLevel"
    }
    if ($source.acquisitionState -notin $allowedAcquisitionStates) {
        throw "$label has an invalid acquisitionState"
    }
    if ($source.licenseStatus -notin $allowedLicenseStatuses) {
        throw "$label has an invalid licenseStatus"
    }
    if ($source.contentUsePolicy -notin $allowedContentUsePolicies) {
        throw "$label has an invalid contentUsePolicy"
    }
    if ($source.modelUsePolicy -notin $allowedModelUsePolicies) {
        throw "$label has an invalid modelUsePolicy"
    }
    if ([long]$source.reviewedAtEpochMillis -le 0) {
        throw "$label.reviewedAtEpochMillis must be positive"
    }
    if ([long]$source.reviewedAtEpochMillis -gt [long]$manifest.reviewedAtEpochMillis) {
        throw "$label cannot be reviewed after the register"
    }

    if (
        $source.licenseStatus -eq 'REFERENCE_ONLY' -and
        $source.contentUsePolicy -ne 'REVIEWED_SYNTHESIS_ONLY'
    ) {
        throw "$label reference-only content may be used only for reviewed synthesis"
    }
    if (
        $source.contentUsePolicy -eq 'REVIEWED_SYNTHESIS_ONLY' -and
        $source.modelUsePolicy -ne 'DERIVED_CONTENT_ONLY'
    ) {
        throw "$label synthesis-only content may expose only independently derived content to models"
    }
    if (
        $source.licenseStatus -eq 'REFERENCE_ONLY' -and
        $source.modelUsePolicy -ne 'DERIVED_CONTENT_ONLY'
    ) {
        throw "$label reference-only content must not be supplied raw to a model or retrieval context"
    }
    if (
        $source.modelUsePolicy -eq 'REVIEWED_EXCERPT_ONLY' -and
        (
            $source.licenseStatus -ne 'LICENSED' -or
            $source.contentUsePolicy -ne 'EXCERPT_ALLOWED'
        )
    ) {
        throw "$label reviewed model excerpts require an explicit license and EXCERPT_ALLOWED"
    }
    if (
        $source.modelUsePolicy -eq 'FULL_CONTENT_ALLOWED' -and
        (
            $source.licenseStatus -ne 'LICENSED' -or
            $source.contentUsePolicy -ne 'ADAPTATION_ALLOWED'
        )
    ) {
        throw "$label full model content requires an explicit license and ADAPTATION_ALLOWED"
    }
    if ($source.contentUsePolicy -ne 'REVIEWED_SYNTHESIS_ONLY') {
        if ($source.licenseStatus -notin @('PUBLIC_OFFICIAL', 'LICENSED')) {
            throw "$label direct expression reuse requires public-official or licensed status"
        }
        if ($source.licenseStatus -eq 'LICENSED') {
            Assert-TrimmedText `
                -Value $source.licenseExpression `
                -Label "$label.licenseExpression" `
                -MaximumLength 256
            Assert-HttpsUri -Value $source.licenseUri -Label "$label.licenseUri"
            Assert-TrimmedText `
                -Value $source.attributionText `
                -Label "$label.attributionText" `
                -MaximumLength 2048
        }
    }

    $hasFingerprint = $null -ne $source.contentFingerprint
    $isAcquired = $source.acquisitionState -in @(
        'ACQUIRED_UNREVIEWED',
        'ACQUIRED_REVIEWED'
    )
    if ($isAcquired) {
        if ($null -eq $source.documentUri) {
            throw "$label acquired content requires documentUri"
        }
        if ($null -eq $source.contentLengthBytes) {
            throw "$label acquired content requires contentLengthBytes"
        }
        if (-not $hasFingerprint -or $source.contentFingerprint -notmatch $uppercaseSha256) {
            throw "$label acquired content requires an uppercase SHA-256 fingerprint"
        }
    } elseif ($hasFingerprint) {
        throw "$label may declare contentFingerprint only after acquisition"
    }

    if (
        (Test-HasPurpose -Source $source -Purpose 'COVERAGE_BASELINE') -and
        $source.baselineId -ne $historicalBaselineId
    ) {
        throw "$label coverage baseline must identify the reviewed 2020 baseline"
    }
    if (
        (
            (Test-HasPurpose -Source $source -Purpose 'CURRENT_CURRICULUM_TEXT') -or
            (Test-HasPurpose -Source $source -Purpose 'REVISION_DELTA')
        ) -and
        $source.baselineId -ne $currentBaselineId
    ) {
        throw "$label current text or revision delta must identify the 2025 baseline"
    }
    $isReviewedTeachingEvidence = (
        $source.acquisitionState -eq 'ACQUIRED_REVIEWED' -and
        (
            (Test-HasPurpose -Source $source -Purpose 'TEACHING_REFERENCE') -or
            (Test-HasPurpose -Source $source -Purpose 'METHOD_REFERENCE') -or
            (Test-HasPurpose -Source $source -Purpose 'WORKED_EXAMPLE_REFERENCE')
        )
    )
    if ($isReviewedTeachingEvidence -and @($source.subjects).Count -ne 1) {
        throw (
            "$label reviewed teaching evidence must bind exactly one subject; " +
            'split broad platforms or multi-subject collections into subject-specific reviewed records'
        )
    }
}

$reviewedSources = @(
    $sources | Where-Object { $_.acquisitionState -eq 'ACQUIRED_REVIEWED' }
)
$officialReviewedSources = @(
    $reviewedSources | Where-Object { $_.authorityLevel -eq 'OFFICIAL' }
)
$reviewedCurrentTextSubjects = @(
    $officialReviewedSources |
        Where-Object {
            $_.baselineId -eq $currentBaselineId -and
            (Test-HasPurpose -Source $_ -Purpose 'CURRENT_CURRICULUM_TEXT')
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$reviewedBaselineSubjects = @(
    $officialReviewedSources |
        Where-Object {
            $_.baselineId -eq $historicalBaselineId -and
            (Test-HasPurpose -Source $_ -Purpose 'COVERAGE_BASELINE')
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$reviewedDeltaSubjects = @(
    $officialReviewedSources |
        Where-Object {
            $_.baselineId -eq $currentBaselineId -and
            (Test-HasPurpose -Source $_ -Purpose 'REVISION_DELTA')
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$reviewedBaseAndDeltaSubjects = @(
    $requiredSubjects |
        Where-Object {
            $_ -in $reviewedBaselineSubjects -and $_ -in $reviewedDeltaSubjects
        }
)
$currentCoverageSubjects = @(
    @($reviewedCurrentTextSubjects + $reviewedBaseAndDeltaSubjects) |
        Sort-Object -Unique
)
$missingCurrentCoverageSubjects = @(
    $requiredSubjects | Where-Object { $_ -notin $currentCoverageSubjects }
)

$reviewedTeachingCounts = [ordered]@{}
$missingTeachingReferenceSubjects = [System.Collections.Generic.List[string]]::new()
$missingMethodReferenceSubjects = [System.Collections.Generic.List[string]]::new()
$missingWorkedExampleReferenceSubjects = [System.Collections.Generic.List[string]]::new()
foreach ($subject in $requiredSubjects) {
    $subjectSources = @(
        $reviewedSources | Where-Object { $subject -in @($_.subjects) }
    )
    $teachingSources = @(
        $subjectSources |
            Where-Object { Test-HasPurpose -Source $_ -Purpose 'TEACHING_REFERENCE' }
    )
    $independentTeachingCount = @(
        $teachingSources.independenceGroup | Sort-Object -Unique
    ).Count
    $methodCount = @(
        $subjectSources |
            Where-Object { Test-HasPurpose -Source $_ -Purpose 'METHOD_REFERENCE' }
    ).Count
    $workedExampleCount = @(
        $subjectSources |
            Where-Object {
                Test-HasPurpose -Source $_ -Purpose 'WORKED_EXAMPLE_REFERENCE'
            }
    ).Count
    $reviewedTeachingCounts[$subject] = [ordered]@{
        independentTeachingReferences = $independentTeachingCount
        methodReferences = $methodCount
        workedExampleReferences = $workedExampleCount
    }
    if ($independentTeachingCount -lt $minimumTeachingReferences) {
        $missingTeachingReferenceSubjects.Add($subject)
    }
    if ($methodCount -lt 1) {
        $missingMethodReferenceSubjects.Add($subject)
    }
    if ($workedExampleCount -lt 1) {
        $missingWorkedExampleReferenceSubjects.Add($subject)
    }
}

$pendingCurrentTextSubjects = @(
    $sources |
        Where-Object {
            $_.acquisitionState -eq 'PENDING_ACQUISITION' -and
            (Test-HasPurpose -Source $_ -Purpose 'CURRENT_CURRICULUM_TEXT')
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$acquiredUnreviewedCurrentTextSubjects = @(
    $sources |
        Where-Object {
            $_.acquisitionState -eq 'ACQUIRED_UNREVIEWED' -and
            (Test-HasPurpose -Source $_ -Purpose 'CURRENT_CURRICULUM_TEXT')
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$metadataOnlyThirdPartySubjects = @(
    $sources |
        Where-Object {
            $_.authorityLevel -eq 'THIRD_PARTY_EDUCATION' -and
            $_.acquisitionState -eq 'METADATA_VERIFIED'
        } |
        ForEach-Object { $_.subjects } |
        Sort-Object -Unique
)
$currentCoverageEvidenceReady = $missingCurrentCoverageSubjects.Count -eq 0
$teachingReferenceEvidenceReady = $missingTeachingReferenceSubjects.Count -eq 0
$methodReferenceEvidenceReady = $missingMethodReferenceSubjects.Count -eq 0
$workedExampleReferenceEvidenceReady = $missingWorkedExampleReferenceSubjects.Count -eq 0
$sourceProductionReady = (
    $currentCoverageEvidenceReady -and
    $teachingReferenceEvidenceReady -and
    $methodReferenceEvidenceReady -and
    $workedExampleReferenceEvidenceReady
)

$report = [ordered]@{
    registerId = [string]$manifest.registerId
    targetBaselineId = [string]$manifest.targetBaselineId
    sourceProductionReady = $sourceProductionReady
    currentCoverageEvidenceReady = $currentCoverageEvidenceReady
    teachingReferenceEvidenceReady = $teachingReferenceEvidenceReady
    methodReferenceEvidenceReady = $methodReferenceEvidenceReady
    workedExampleReferenceEvidenceReady = $workedExampleReferenceEvidenceReady
    reviewedCurrentTextSubjects = $reviewedCurrentTextSubjects
    reviewedBaseAndDeltaSubjects = $reviewedBaseAndDeltaSubjects
    missingCurrentCoverageSubjects = $missingCurrentCoverageSubjects
    missingTeachingReferenceSubjects = @($missingTeachingReferenceSubjects)
    missingMethodReferenceSubjects = @($missingMethodReferenceSubjects)
    missingWorkedExampleReferenceSubjects = @($missingWorkedExampleReferenceSubjects)
    pendingCurrentTextSubjects = $pendingCurrentTextSubjects
    acquiredUnreviewedCurrentTextSubjects = $acquiredUnreviewedCurrentTextSubjects
    metadataOnlyThirdPartySubjects = $metadataOnlyThirdPartySubjects
    reviewedTeachingCounts = $reviewedTeachingCounts
    sourceCounts = [ordered]@{
        total = $sources.Count
        pendingAcquisition = @(
            $sources | Where-Object { $_.acquisitionState -eq 'PENDING_ACQUISITION' }
        ).Count
        metadataVerified = @(
            $sources | Where-Object { $_.acquisitionState -eq 'METADATA_VERIFIED' }
        ).Count
        acquiredUnreviewed = @(
            $sources | Where-Object { $_.acquisitionState -eq 'ACQUIRED_UNREVIEWED' }
        ).Count
        acquiredReviewed = $reviewedSources.Count
        revisionInterpretations = @(
            $sources |
                Where-Object {
                    Test-HasPurpose -Source $_ -Purpose 'REVISION_INTERPRETATION'
                }
        ).Count
    }
}

$report | ConvertTo-Json -Depth 12

if ($RequireProductionReady -and -not $sourceProductionReady) {
    throw (
        'Knowledge source production gate is not ready. Current-text evidence missing: ' +
        ($missingCurrentCoverageSubjects -join ', ') +
        '; reviewed multi-source teaching references missing: ' +
        (@($missingTeachingReferenceSubjects) -join ', ') +
        '; method references missing: ' +
        (@($missingMethodReferenceSubjects) -join ', ') +
        '; worked-example references missing: ' +
        (@($missingWorkedExampleReferenceSubjects) -join ', ')
    )
}
