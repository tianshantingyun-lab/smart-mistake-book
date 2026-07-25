[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$knowledgeDirectory = Join-Path $ProjectRoot 'core\data\src\main\resources\knowledge'
$synthesisOnly = 'REVIEWED_SYNTHESIS_ONLY'
$directPolicies = @('EXCERPT_ALLOWED', 'ADAPTATION_ALLOWED')

if (-not (Test-Path -LiteralPath $knowledgeDirectory -PathType Container)) {
    throw "Knowledge resource directory does not exist: $knowledgeDirectory"
}

$reports = foreach ($file in Get-ChildItem -LiteralPath $knowledgeDirectory -Filter '*.json' -File) {
    $raw = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json
    if ($null -eq $raw.materials) {
        continue
    }

    $schemaVersion = [int]$raw.schemaVersion
    if ($schemaVersion -notin @(1, 2)) {
        throw "Unsupported teaching-material schema $schemaVersion in $($file.Name)"
    }

    $sourcePolicies = @{}
    foreach ($source in @($raw.sources)) {
        $policy = if ($schemaVersion -eq 1) {
            $synthesisOnly
        } else {
            [string]$source.contentUsePolicy
        }
        if ($policy -notin @($synthesisOnly) + $directPolicies) {
            throw "Unknown content-use policy '$policy' in $($file.Name)"
        }
        if (
            [string]$source.licenseStatus -eq 'REFERENCE_ONLY' -and
            $policy -ne $synthesisOnly
        ) {
            throw "Reference-only source '$($source.sourceId)' requests direct reuse"
        }
        if ($policy -in $directPolicies) {
            foreach ($field in @('licenseExpression', 'licenseUri', 'attributionText')) {
                if ([string]::IsNullOrWhiteSpace([string]$source.$field)) {
                    throw "Direct reuse source '$($source.sourceId)' is missing $field"
                }
            }
            $licenseUri = [uri][string]$source.licenseUri
            if (
                -not $licenseUri.IsAbsoluteUri -or
                $licenseUri.Scheme -ne 'https' -or
                [string]::IsNullOrWhiteSpace($licenseUri.Host)
            ) {
                throw "Direct reuse source '$($source.sourceId)' has an invalid licenseUri"
            }
        }
        $sourcePolicies[[string]$source.sourceId] = [pscustomobject]@{
            licenseStatus = [string]$source.licenseStatus
            contentUsePolicy = $policy
        }
    }

    $directMaterialCount = 0
    foreach ($material in @($raw.materials)) {
        $derivation = [string]$material.derivationKind
        if ($derivation -eq 'REVIEWED_SYNTHESIS') {
            continue
        }
        $directMaterialCount++
        $source = $sourcePolicies[[string]$material.sourceId]
        if ($null -eq $source) {
            throw (
                "Directly reused material '$($material.slug)' must name a sidecar source " +
                "with explicit reuse terms"
            )
        }
        $allowed = switch ($derivation) {
            'PUBLIC_OFFICIAL_EXCERPT' {
                $source.licenseStatus -eq 'PUBLIC_OFFICIAL' -and
                $source.contentUsePolicy -in $directPolicies
            }
            'LICENSED_EXCERPT' {
                $source.licenseStatus -eq 'LICENSED' -and
                $source.contentUsePolicy -in $directPolicies
            }
            'LICENSED_ADAPTATION' {
                $source.licenseStatus -eq 'LICENSED' -and
                $source.contentUsePolicy -eq 'ADAPTATION_ALLOWED'
            }
            default { $false }
        }
        if (-not $allowed) {
            throw (
                "Material '$($material.slug)' derivation '$derivation' is not permitted " +
                "by source '$($material.sourceId)'"
            )
        }
    }

    [pscustomobject]@{
        file = $file.Name
        schemaVersion = $schemaVersion
        sourceCount = @($raw.sources).Count
        materialCount = @($raw.materials).Count
        directReuseMaterialCount = $directMaterialCount
        legacySourcesDefaultedToSynthesisOnly = (
            $schemaVersion -eq 1 -and @($raw.sources).Count -gt 0
        )
    }
}

[ordered]@{
    valid = $true
    auditedFiles = @($reports).Count
    files = @($reports)
} | ConvertTo-Json -Depth 6
