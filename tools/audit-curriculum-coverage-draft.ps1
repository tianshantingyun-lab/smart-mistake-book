[CmdletBinding()]
param(
    [switch]$RequireNoWarnings,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ManifestPath,
    [string]$CandidatePath,
    [string]$LedgerPath,
    [string]$SourceRegisterPath
)

$ErrorActionPreference = 'Stop'
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
$allowedCandidateKinds = @(
    'CURRICULUM_STATEMENT',
    'SCOPE_SUMMARY'
)
$allowedTeachingSupport = @(
    'METHOD_MODEL',
    'WORKED_EXAMPLE',
    'COMPLETE_SOLUTION',
    'DERIVATION'
)
$requiredForbiddenAuthorities = @(
    'AUTONOMOUS_QUESTION_GENERATION',
    'ASSESSMENT_ITEM_GENERATION',
    'REVIEW_SCHEDULING'
)

if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $ProjectRoot (
        'knowledge-production\' +
        'curriculum-coverage-extraction-manifest-2025-v1.json'
    )
}
if ([string]::IsNullOrWhiteSpace($CandidatePath)) {
    $CandidatePath = Join-Path $ProjectRoot (
        'knowledge-production\knowledge-coverage-candidates-2025-v1.json'
    )
}
if ([string]::IsNullOrWhiteSpace($LedgerPath)) {
    $LedgerPath = Join-Path $ProjectRoot (
        'knowledge-production\knowledge-coverage-ledger-2025-v1.json'
    )
}
if ([string]::IsNullOrWhiteSpace($SourceRegisterPath)) {
    $SourceRegisterPath = Join-Path $ProjectRoot (
        'core\data\src\main\resources\knowledge\source-register-2025-v1.json'
    )
}

function Read-JsonDocument {
    param(
        [string]$Path,
        [string]$Label
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist: $Path"
    }
    try {
        return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    } catch {
        throw "$Label is not valid JSON: $Path"
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

function Assert-ExactSet {
    param(
        [object[]]$Expected,
        [object[]]$Actual,
        [string]$Label
    )
    if (@(Compare-Object $Expected $Actual).Count -ne 0) {
        throw "$Label does not match its authoritative set"
    }
}

$manifest = Read-JsonDocument -Path $ManifestPath -Label 'Extraction manifest'
$candidates = Read-JsonDocument -Path $CandidatePath -Label 'Coverage candidates'
$ledger = Read-JsonDocument -Path $LedgerPath -Label 'Coverage ledger'
$register = Read-JsonDocument -Path $SourceRegisterPath -Label 'Source register'

if (
    [int]$manifest.schemaVersion -ne 1 -or
    [int]$candidates.schemaVersion -ne 1
) {
    throw 'Only schemaVersion 1 coverage-draft artifacts are supported'
}
if (
    [string]$candidates.manifestId -ne [string]$manifest.manifestId -or
    [string]$candidates.targetBaselineId -ne [string]$manifest.targetBaselineId -or
    [string]$candidates.sourceRegisterId -ne [string]$register.registerId -or
    [string]$ledger.sourceRegisterId -ne [string]$register.registerId
) {
    throw 'Coverage-draft artifacts do not share the authoritative manifest and register'
}
if (
    $candidates.mappingState -ne 'DRAFT_UNREVIEWED' -or
    @($candidates.subjects | Where-Object {
            $_.mappingState -ne 'DRAFT_UNREVIEWED'
        }).Count -gt 0
) {
    throw 'Mechanical curriculum extraction may only emit DRAFT_UNREVIEWED mappings'
}

$boundary = $candidates.knowledgeBaseBoundary
if (
    $null -eq $boundary -or
    $boundary.identity -ne 'KNOWLEDGE_AND_TEACHING_SUPPORT_NOT_QUESTION_BANK'
) {
    throw 'Coverage candidates must preserve the non-question-bank identity'
}
Assert-ExactSet `
    -Expected $allowedTeachingSupport `
    -Actual @($boundary.allowedTeachingSupport) `
    -Label 'Allowed teaching-support material types'
Assert-ExactSet `
    -Expected $requiredForbiddenAuthorities `
    -Actual @($boundary.forbiddenAuthorities) `
    -Label 'Forbidden knowledge-base authorities'

$manifestSubjects = @($manifest.subjects)
$candidateSubjects = @($candidates.subjects)
$ledgerSubjects = @($ledger.subjects)
Assert-ExactSet `
    -Expected $requiredSubjects `
    -Actual @($manifestSubjects.subject) `
    -Label 'Manifest subjects'
Assert-ExactSet `
    -Expected $requiredSubjects `
    -Actual @($candidateSubjects.subject) `
    -Label 'Candidate subjects'
Assert-ExactSet `
    -Expected $requiredSubjects `
    -Actual @($ledgerSubjects.subject) `
    -Label 'Ledger subjects'

$sourcesById = @{}
foreach ($source in @($register.sources)) {
    $sourcesById[[string]$source.sourceId] = $source
}
$candidateSubjectsByName = @{}
foreach ($subject in $candidateSubjects) {
    $candidateSubjectsByName[[string]$subject.subject] = $subject
}
$ledgerSubjectsByName = @{}
foreach ($subject in $ledgerSubjects) {
    $ledgerSubjectsByName[[string]$subject.subject] = $subject
}

$allCandidateKeys = [System.Collections.Generic.List[string]]::new()
$subjectReports = foreach ($manifestSubject in $manifestSubjects) {
    $subject = [string]$manifestSubject.subject
    $candidateSubject = $candidateSubjectsByName[$subject]
    $ledgerSubject = $ledgerSubjectsByName[$subject]
    $source = $sourcesById[[string]$manifestSubject.sourceId]
    if (
        $null -eq $source -or
        $candidateSubject.sourceId -ne $manifestSubject.sourceId -or
        $ledgerSubject.curriculumSourceId -ne $manifestSubject.sourceId -or
        $candidateSubject.sourceFingerprint -ne $source.contentFingerprint -or
        [long]$candidateSubject.sourceBytes -ne [long]$source.contentLengthBytes
    ) {
        throw "$subject coverage draft does not match its registered source identity"
    }

    $manifestModules = @($manifestSubject.modules)
    $candidateModules = @($candidateSubject.modules)
    $ledgerModules = @($ledgerSubject.modules)
    Assert-ExactSet `
        -Expected @($manifestModules.slug) `
        -Actual @($candidateModules.slug) `
        -Label "$subject candidate modules"
    Assert-ExactSet `
        -Expected @($manifestModules.slug) `
        -Actual @($ledgerModules.slug) `
        -Label "$subject ledger modules"

    $candidateModulesBySlug = @{}
    foreach ($module in $candidateModules) {
        $candidateModulesBySlug[[string]$module.slug] = $module
    }
    $ledgerModulesBySlug = @{}
    foreach ($module in $ledgerModules) {
        $ledgerModulesBySlug[[string]$module.slug] = $module
    }

    $statementCount = 0
    $scopeSummaryCount = 0
    $warningCount = 0
    foreach ($manifestModule in $manifestModules) {
        $slug = [string]$manifestModule.slug
        $candidateModule = $candidateModulesBySlug[$slug]
        $ledgerModule = $ledgerModulesBySlug[$slug]
        Assert-ExactSet `
            -Expected @($manifestModule.requirementTypes) `
            -Actual @($candidateModule.requirementTypes) `
            -Label "$subject/$slug candidate course stages"
        Assert-ExactSet `
            -Expected @($manifestModule.requirementTypes) `
            -Actual @($ledgerModule.courseStages) `
            -Label "$subject/$slug ledger course stages"
        if (
            $candidateModule.name -ne $manifestModule.name -or
            $ledgerModule.name -ne $manifestModule.name -or
            $candidateModule.extractionMode -ne $manifestModule.mode
        ) {
            throw "$subject/$slug module identity differs from the extraction manifest"
        }

        $statements = @($candidateModule.candidateStatements)
        $points = @($ledgerModule.knowledgePoints)
        if ($statements.Count -eq 0 -or $points.Count -eq 0) {
            throw "$subject/$slug must keep at least one explicit draft candidate"
        }
        Assert-Unique -Values @($statements.slug) -Label "$subject/$slug candidates"
        Assert-Unique -Values @($points.slug) -Label "$subject/$slug ledger points"
        Assert-ExactSet `
            -Expected @($statements.slug) `
            -Actual @($points.slug) `
            -Label "$subject/$slug candidate-to-ledger keys"

        $pointsBySlug = @{}
        foreach ($point in $points) {
            $pointsBySlug[[string]$point.slug] = $point
        }
        foreach ($statement in $statements) {
            if ($statement.candidateKind -notin $allowedCandidateKinds) {
                throw "$subject/$slug has an unsupported candidate kind"
            }
            if (
                [int]$statement.sourcePage -lt [int]$manifestModule.pages[0] -or
                [int]$statement.sourcePage -gt [int]$manifestModule.pages[1]
            ) {
                throw "$subject/$slug candidate source page is outside its module"
            }
            $point = $pointsBySlug[[string]$statement.slug]
            if (
                $point.name -ne $statement.name -or
                $point.sourceLocator -ne $statement.sourceLocator
            ) {
                throw "$subject/$slug candidate and ledger payloads differ"
            }
            $allCandidateKeys.Add("$subject/$slug/$($statement.slug)")
            if ($statement.candidateKind -eq 'SCOPE_SUMMARY') {
                $scopeSummaryCount += 1
            } else {
                $statementCount += 1
            }
        }
        $warningCount += @($candidateModule.warnings).Count
    }

    [pscustomobject]@{
        subject = $subject
        moduleCount = $manifestModules.Count
        curriculumStatementCandidateCount = $statementCount
        scopeSummaryCandidateCount = $scopeSummaryCount
        warningCount = $warningCount
    }
}

Assert-Unique -Values @($allCandidateKeys) -Label 'All curriculum draft candidate keys'
$warningCount = [int](
    @($subjectReports.warningCount | Measure-Object -Sum).Sum
)
$report = [ordered]@{
    draftEvidenceValid = $true
    mappingState = [string]$candidates.mappingState
    targetBaselineId = [string]$candidates.targetBaselineId
    subjectCount = $candidateSubjects.Count
    moduleCount = [int](
        @($subjectReports.moduleCount | Measure-Object -Sum).Sum
    )
    candidateCount = $allCandidateKeys.Count
    warningCount = $warningCount
    warningsResolved = ($warningCount -eq 0)
    questionBankAuthorityGranted = $false
    subjects = @($subjectReports)
}

$report | ConvertTo-Json -Depth 6

if ($RequireNoWarnings -and $warningCount -gt 0) {
    throw (
        'Curriculum coverage draft still has extraction warnings: ' +
        [string]$warningCount
    )
}
