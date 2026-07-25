[CmdletBinding()]
param([switch]$ValidateOnly)

$ErrorActionPreference = 'Stop'

$workspaceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'android-workspace-security.ps1')

if ($ValidateOnly) {
    Assert-AndroidWorkspaceSecurity -WorkspaceRoot $workspaceRoot
} else {
    Protect-AndroidWorkspaceSecurity -WorkspaceRoot $workspaceRoot
}

Get-AndroidWorkspaceSecuritySummary -WorkspaceRoot $workspaceRoot
