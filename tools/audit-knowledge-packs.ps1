[CmdletBinding()]
param(
    [switch]$RequireFullCoverage,
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceRegisterPath,
    [string]$CoverageLedgerPath
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
$knowledgeDirectory = Join-Path $ProjectRoot 'core\data\src\main\resources\knowledge'
$sourceRegisterAuditScript = Join-Path $PSScriptRoot 'audit-knowledge-source-register.ps1'
$coverageLedgerAuditScript = Join-Path $PSScriptRoot 'audit-knowledge-coverage-ledger.ps1'
$coverageDraftAuditScript = Join-Path $PSScriptRoot 'audit-curriculum-coverage-draft.ps1'

if (-not (Test-Path -LiteralPath $knowledgeDirectory -PathType Container)) {
    throw "Knowledge resource directory does not exist: $knowledgeDirectory"
}
if (-not (Test-Path -LiteralPath $sourceRegisterAuditScript -PathType Leaf)) {
    throw "Knowledge source-register audit does not exist: $sourceRegisterAuditScript"
}
if (-not (Test-Path -LiteralPath $coverageLedgerAuditScript -PathType Leaf)) {
    throw "Knowledge coverage-ledger audit does not exist: $coverageLedgerAuditScript"
}
if (-not (Test-Path -LiteralPath $coverageDraftAuditScript -PathType Leaf)) {
    throw "Curriculum coverage-draft audit does not exist: $coverageDraftAuditScript"
}

$sourceAuditArguments = @{
    ProjectRoot = $ProjectRoot
}
if (-not [string]::IsNullOrWhiteSpace($SourceRegisterPath)) {
    $sourceAuditArguments.RegisterPath = $SourceRegisterPath
}
$sourceAudit = (
    & $sourceRegisterAuditScript @sourceAuditArguments | Out-String
) | ConvertFrom-Json
$coverageLedgerAuditArguments = @{
    ProjectRoot = $ProjectRoot
}
if (-not [string]::IsNullOrWhiteSpace($SourceRegisterPath)) {
    $coverageLedgerAuditArguments.SourceRegisterPath = $SourceRegisterPath
}
if (-not [string]::IsNullOrWhiteSpace($CoverageLedgerPath)) {
    $coverageLedgerAuditArguments.LedgerPath = $CoverageLedgerPath
}
$coverageLedgerAudit = (
    & $coverageLedgerAuditScript @coverageLedgerAuditArguments | Out-String
) | ConvertFrom-Json
$coverageDraftAuditArguments = @{
    ProjectRoot = $ProjectRoot
}
if (-not [string]::IsNullOrWhiteSpace($SourceRegisterPath)) {
    $coverageDraftAuditArguments.SourceRegisterPath = $SourceRegisterPath
}
if (-not [string]::IsNullOrWhiteSpace($CoverageLedgerPath)) {
    $coverageDraftAuditArguments.LedgerPath = $CoverageLedgerPath
}
$coverageDraftAudit = (
    & $coverageDraftAuditScript @coverageDraftAuditArguments | Out-String
) | ConvertFrom-Json

$knowledgeDocuments = @(
    Get-ChildItem -LiteralPath $knowledgeDirectory -Filter '*.json' -File |
        ForEach-Object {
            [pscustomobject]@{
                file = $_
                raw = Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json
            }
        }
)
$packDocuments = @(
    $knowledgeDocuments | Where-Object { $null -ne $_.raw.subjects }
)
$sidecarDocuments = @(
    $knowledgeDocuments | Where-Object { $null -ne $_.raw.materials }
)
$atomicNodeIdsByPack = @{}

$packs = foreach ($document in $packDocuments) {
    $file = $document.file
    $raw = $document.raw
    $schemaVersion = [int]$raw.schemaVersion
    if ($schemaVersion -eq 1) {
        $pointCount = @($raw.subjects | ForEach-Object { @($_.knowledgePoints).Count } |
            Measure-Object -Sum).Sum
        [pscustomobject]@{
            file = $file.Name
            packId = [string]$raw.packId
            baselineId = 'moe-high-school-2017-2020'
            catalogLevel = 'HISTORICAL_SAMPLE'
            teachingSupportLevel = 'HISTORICAL_SAMPLE'
            subjects = @($raw.subjects.subject)
            topics = @($raw.subjects).Count
            fineGrainedPoints = [int]$pointCount
        }
        continue
    }
    if ($schemaVersion -ne 2) {
        throw "Unsupported knowledge-pack schema $schemaVersion in $($file.Name)"
    }
    $atomicNodeIdsByPack[[string]$raw.packId] = @(
        $raw.subjects |
            ForEach-Object {
                $subjectKey = ([string]$_.subject).ToLowerInvariant()
                $taxonomyVersion = [string]$raw.taxonomyVersion
                $_.topics |
                    ForEach-Object {
                        $_.knowledgePoints |
                            ForEach-Object {
                                "kb:${taxonomyVersion}:${subjectKey}:atomic:$($_.slug)"
                            }
                    }
            }
    )
    $pointKeys = @(
        $raw.subjects |
            ForEach-Object {
                $subject = [string]$_.subject
                $_.topics |
                    ForEach-Object {
                        $topicSlug = [string]$_.slug
                        $_.knowledgePoints |
                            ForEach-Object {
                                "$subject/$topicSlug/$($_.slug)"
                            }
                    }
            }
    )
    $topicCount = @($raw.subjects | ForEach-Object { @($_.topics).Count } |
        Measure-Object -Sum).Sum
    $pointCount = @(
        $raw.subjects |
            ForEach-Object { $_.topics } |
            ForEach-Object { @($_.knowledgePoints).Count } |
            Measure-Object -Sum
    ).Sum
    [pscustomobject]@{
        file = $file.Name
        packId = [string]$raw.packId
        baselineId = [string]$raw.coverage.baselineId
        catalogLevel = [string]$raw.coverage.catalogLevel
        teachingSupportLevel = [string]$raw.coverage.teachingSupportLevel
        subjects = @($raw.subjects.subject)
        topics = [int]$topicCount
        fineGrainedPoints = [int]$pointCount
        pointKeys = $pointKeys
    }
}

if (@($packs.packId | Sort-Object -Unique).Count -ne @($packs).Count) {
    throw 'Knowledge-pack ids must be unique across bundled resources'
}

$currentPacks = @($packs | Where-Object { $_.baselineId -eq $currentBaselineId })
$currentSubjects = @(
    $currentPacks.subjects |
        Sort-Object -Unique
)
$missingSubjects = @($requiredSubjects | Where-Object { $_ -notin $currentSubjects })
$catalogDeclaredFull = @($currentPacks | Where-Object { $_.catalogLevel -eq 'FULL' }).Count -gt 0
$teachingDeclaredFull = @(
    $currentPacks |
        Where-Object { $_.teachingSupportLevel -eq 'FULL' }
).Count -gt 0
$fullCurrentPacks = @(
    $currentPacks |
        Where-Object {
            $_.catalogLevel -eq 'FULL' -and $_.teachingSupportLevel -eq 'FULL'
        }
)
$coverageScopeEvidence = @(
    foreach ($pack in $fullCurrentPacks) {
        $packPointKeys = @($pack.pointKeys | Sort-Object -Unique)
        $requiredPointKeys = @(
            $coverageLedgerAudit.requiredPointKeys | Sort-Object -Unique
        )
        $missingLedgerPoints = @(
            $requiredPointKeys | Where-Object { $_ -notin $packPointKeys }
        )
        $unexpectedPackPoints = @(
            $packPointKeys | Where-Object { $_ -notin $requiredPointKeys }
        )
        [pscustomobject]@{
            packId = [string]$pack.packId
            scopeMatchesLedger = (
                [bool]$coverageLedgerAudit.coverageLedgerReady -and
                $missingLedgerPoints.Count -eq 0 -and
                $unexpectedPackPoints.Count -eq 0
            )
            missingLedgerPointCount = $missingLedgerPoints.Count
            missingLedgerPoints = $missingLedgerPoints
            unexpectedPackPointCount = $unexpectedPackPoints.Count
            unexpectedPackPoints = $unexpectedPackPoints
        }
    }
)
$coverageScopeEvidenceReady = @(
    $coverageScopeEvidence | Where-Object { $_.scopeMatchesLedger }
).Count -gt 0
$teachingSupportEvidence = @(
    foreach ($pack in $fullCurrentPacks) {
        $sidecars = @(
            $sidecarDocuments |
                Where-Object { [string]$_.raw.packId -eq [string]$pack.packId }
        )
        $materials = @(
            $sidecars | ForEach-Object { $_.raw.materials }
        )
        $materialSubjects = @($materials.subject | Sort-Object -Unique)
        $missingMaterialSubjects = @(
            $requiredSubjects | Where-Object { $_ -notin $materialSubjects }
        )
        $missingMethodSubjects = @(
            $requiredSubjects |
                Where-Object {
                    $subject = $_
                    @(
                        $materials |
                            Where-Object {
                                $_.subject -eq $subject -and $_.type -eq 'METHOD_MODEL'
                            }
                    ).Count -eq 0
                }
        )
        $missingWorkedExampleSubjects = @(
            $requiredSubjects |
                Where-Object {
                    $subject = $_
                    @(
                        $materials |
                            Where-Object {
                                $_.subject -eq $subject -and $_.type -eq 'WORKED_EXAMPLE'
                            }
                    ).Count -eq 0
                }
        )
        $boundNodeIds = @(
            $materials |
                ForEach-Object { $_.bindings } |
                ForEach-Object { $_.knowledgeNodeId } |
                Sort-Object -Unique
        )
        $missingPointBindings = @(
            @($atomicNodeIdsByPack[[string]$pack.packId]) |
                Where-Object { $_ -notin $boundNodeIds }
        )
        [pscustomobject]@{
            packId = [string]$pack.packId
            ready = (
                $materials.Count -gt 0 -and
                $missingMaterialSubjects.Count -eq 0 -and
                $missingMethodSubjects.Count -eq 0 -and
                $missingWorkedExampleSubjects.Count -eq 0 -and
                $missingPointBindings.Count -eq 0
            )
            materialCount = $materials.Count
            missingMaterialSubjects = $missingMaterialSubjects
            missingMethodSubjects = $missingMethodSubjects
            missingWorkedExampleSubjects = $missingWorkedExampleSubjects
            missingPointBindingCount = $missingPointBindings.Count
            missingPointBindings = $missingPointBindings
        }
    }
)
$teachingSupportEvidenceReady = @(
    $teachingSupportEvidence | Where-Object { $_.ready }
).Count -gt 0
$productionReadyPackIds = @(
    $coverageScopeEvidence |
        Where-Object { $_.scopeMatchesLedger } |
        ForEach-Object { $_.packId } |
        Where-Object {
            $packId = $_
            @(
                $teachingSupportEvidence |
                    Where-Object { $_.packId -eq $packId -and $_.ready }
            ).Count -gt 0
        }
)
$contentPackEvidenceReady = $productionReadyPackIds.Count -gt 0
$fullCoverageReady = (
    $currentPacks.Count -gt 0 -and
    $missingSubjects.Count -eq 0 -and
    $catalogDeclaredFull -and
    $teachingDeclaredFull -and
    $contentPackEvidenceReady -and
    [bool]$coverageDraftAudit.draftEvidenceValid -and
    $sourceAudit.sourceProductionReady
)

$report = [ordered]@{
    targetBaselineId = $currentBaselineId
    fullCoverageReady = $fullCoverageReady
    currentSubjects = $currentSubjects
    missingSubjects = $missingSubjects
    currentFineGrainedPoints = [int](
        @($currentPacks.fineGrainedPoints | Measure-Object -Sum).Sum
    )
    catalogDeclaredFull = $catalogDeclaredFull
    teachingSupportDeclaredFull = $teachingDeclaredFull
    coverageLedgerReady = [bool]$coverageLedgerAudit.coverageLedgerReady
    coverageDraftEvidenceValid = [bool]$coverageDraftAudit.draftEvidenceValid
    coverageDraft = $coverageDraftAudit
    coverageScopeEvidenceReady = $coverageScopeEvidenceReady
    coverageScopeEvidence = $coverageScopeEvidence
    coverageLedger = $coverageLedgerAudit
    teachingSupportEvidenceReady = $teachingSupportEvidenceReady
    teachingSupportEvidence = $teachingSupportEvidence
    contentPackEvidenceReady = $contentPackEvidenceReady
    productionReadyPackIds = $productionReadyPackIds
    sourceProductionReady = [bool]$sourceAudit.sourceProductionReady
    sourceEvidence = $sourceAudit
    packs = @($packs)
}

$report | ConvertTo-Json -Depth 8

if ($RequireFullCoverage -and -not $fullCoverageReady) {
    throw (
        'Full 2025 high-school knowledge coverage is not ready. Missing subjects: ' +
        ($missingSubjects -join ', ') +
        '; source-production gate ready: ' +
        [string]$sourceAudit.sourceProductionReady +
        '; independent coverage-ledger gate ready: ' +
        [string]$coverageLedgerAudit.coverageLedgerReady
    )
}
