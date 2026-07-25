$script:AndroidWorkspaceExpectedToolHashes = [ordered]@{
    '.toolchains\android-sdk\platform-tools\adb.exe' = '957E46B8615F7AF5B7292A2DDABE98D2E61940C3FB2B0545756507F080613E71'
    '.toolchains\android-sdk\emulator\emulator.exe' = '7F101CA769C15E8B6311801C1487F28583CE643FA87D19A9E0DA4CC230BE7653'
    '.toolchains\android-sdk\emulator\qemu\windows-x86_64\qemu-system-x86_64-headless.exe' = 'B8E263A00F9151A494B480F38D39188E73DBF338B27015679311246C59C3DBEC'
    '.toolchains\android-sdk\emulator\qemu\windows-x86_64\qemu-system-x86_64.exe' = '2C389BF3C28F37968A226E6CBD9C50F8B051C7DB9DB471E7E10548F645652AD3'
    '.toolchains\android-sdk\cmdline-tools\latest\bin\sdkmanager.bat' = '11D021F90186F7FCC0B2D97623348C9C857109636B29D70FA1B804A4D603F5E7'
    '.toolchains\android-sdk\cmdline-tools\latest\bin\avdmanager.bat' = '2307299B666C61610D1F35B36EA05C2D24C43DDEC33E755D40A52797A21D2AA1'
    '.toolchains\gradle\gradle-9.6.1\bin\gradle.bat' = '619717E564DC38320E4A73F615CC6C45931AC25F6DF0567B303DDDD7E7CAD9F7'
}

function Resolve-AndroidWorkspaceSecurityRoot {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $resolvedRoot = (Resolve-Path -LiteralPath $WorkspaceRoot -ErrorAction Stop).Path.TrimEnd('\')
    if (-not [string]::Equals(
        [IO.Path]::GetPathRoot($resolvedRoot),
        'D:\',
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Android workspace security controls only accept a D: workspace, found $resolvedRoot"
    }

    $rootItem = Get-Item -Force -LiteralPath $resolvedRoot
    if (-not $rootItem.PSIsContainer -or
        ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Android workspace security root must be a physical directory: $resolvedRoot"
    }

    $requiredMarker = Join-Path $resolvedRoot 'tools\android-env.ps1'
    if (-not (Test-Path -LiteralPath $requiredMarker -PathType Leaf)) {
        throw "Android workspace marker is missing: $requiredMarker"
    }

    $resolvedRoot
}

function Get-AndroidWorkspaceAllowedWriterSids {
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    @(
        $currentIdentity.User.Value
        'S-1-5-18'
        'S-1-5-32-544'
    )
}

function Get-AndroidWorkspaceSecurityTargets {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $resolvedRoot = Resolve-AndroidWorkspaceSecurityRoot -WorkspaceRoot $WorkspaceRoot
    @(
        $resolvedRoot
        (Join-Path $resolvedRoot 'tools')
        (Join-Path $resolvedRoot '.toolchains')
        (Join-Path $resolvedRoot '.android')
    )
}

function Assert-AndroidWorkspaceSecurityTarget {
    param(
        [Parameter(Mandatory)][string]$WorkspaceRoot,
        [Parameter(Mandatory)][string]$Path
    )

    $allowedTargets = @(Get-AndroidWorkspaceSecurityTargets -WorkspaceRoot $WorkspaceRoot)
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (-not ($allowedTargets | Where-Object {
        [string]::Equals($_, $fullPath, [StringComparison]::OrdinalIgnoreCase)
    })) {
        throw "Refusing to change ACL outside the exact Android workspace security targets: $fullPath"
    }

    $item = Get-Item -Force -LiteralPath $fullPath -ErrorAction Stop
    if (-not $item.PSIsContainer -or
        ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "ACL target must be a physical directory: $fullPath"
    }

    $fullPath
}

function New-AndroidWorkspaceDirectorySecurity {
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $security.SetOwner($currentIdentity.User)
    $security.SetAccessRuleProtection($true, $false)

    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    foreach ($sidValue in @(Get-AndroidWorkspaceAllowedWriterSids)) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidValue)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow
        )
        $security.AddAccessRule($rule)
    }

    $security
}

function Get-AndroidWorkspaceUnauthorizedWriteRules {
    param([Parameter(Mandatory)][string]$Path)

    $allowedWriterSids = @(Get-AndroidWorkspaceAllowedWriterSids)
    $writeRights = [Security.AccessControl.FileSystemRights]::Write -bor
        [Security.AccessControl.FileSystemRights]::Modify -bor
        [Security.AccessControl.FileSystemRights]::FullControl -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership

    @((Get-Acl -LiteralPath $Path).Access | Where-Object {
        if ($_.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            (([int64]$_.FileSystemRights -band [int64]$writeRights) -eq 0)) {
            return $false
        }

        try {
            $ruleSid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } catch {
            return $true
        }
        $ruleSid -notin $allowedWriterSids
    })
}

function Assert-NoAndroidWorkspaceReparsePoint {
    param(
        [Parameter(Mandatory)][string]$WorkspaceRoot,
        [Parameter(Mandatory)][string]$Path
    )

    $resolvedRoot = Resolve-AndroidWorkspaceSecurityRoot -WorkspaceRoot $WorkspaceRoot
    $fullPath = [IO.Path]::GetFullPath($Path)
    $rootPrefix = $resolvedRoot.TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Integrity path escapes the Android workspace: $fullPath"
    }

    $relativePath = [IO.Path]::GetRelativePath($resolvedRoot, $fullPath)
    $currentPath = $resolvedRoot
    foreach ($segment in $relativePath -split '[\\/]') {
        $currentPath = Join-Path $currentPath $segment
        $item = Get-Item -Force -LiteralPath $currentPath -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Integrity path contains a reparse point: $currentPath"
        }
    }
}

function Assert-AndroidWorkspaceToolIntegrity {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $resolvedRoot = Resolve-AndroidWorkspaceSecurityRoot -WorkspaceRoot $WorkspaceRoot
    foreach ($entry in $script:AndroidWorkspaceExpectedToolHashes.GetEnumerator()) {
        $toolPath = Join-Path $resolvedRoot $entry.Key
        Assert-NoAndroidWorkspaceReparsePoint -WorkspaceRoot $resolvedRoot -Path $toolPath
        if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) {
            throw "Required Android workspace tool is missing: $toolPath"
        }

        $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $toolPath).Hash
        if (-not [string]::Equals($actualHash, $entry.Value, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Android workspace tool integrity check failed: $toolPath"
        }
    }
}

function Assert-AndroidWorkspaceAdbIdentity {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $resolvedRoot = Resolve-AndroidWorkspaceSecurityRoot -WorkspaceRoot $WorkspaceRoot
    foreach ($relativePath in @('.android\adbkey', '.android\adbkey.pub')) {
        $keyPath = Join-Path $resolvedRoot $relativePath
        Assert-NoAndroidWorkspaceReparsePoint -WorkspaceRoot $resolvedRoot -Path $keyPath
        $keyItem = Get-Item -Force -LiteralPath $keyPath -ErrorAction Stop
        if ($keyItem.PSIsContainer -or $keyItem.Length -le 0) {
            throw "Workspace ADB identity file is invalid: $keyPath"
        }
    }
}

function Assert-AndroidWorkspaceSecurity {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $targets = @(Get-AndroidWorkspaceSecurityTargets -WorkspaceRoot $WorkspaceRoot)
    foreach ($target in $targets) {
        $validatedTarget = Assert-AndroidWorkspaceSecurityTarget `
            -WorkspaceRoot $WorkspaceRoot `
            -Path $target
        $acl = Get-Acl -LiteralPath $validatedTarget
        if (-not $acl.AreAccessRulesProtected) {
            throw "Android workspace ACL still inherits permissions: $validatedTarget"
        }

        $allowedWriterSids = @(Get-AndroidWorkspaceAllowedWriterSids)
        $fullControlSids = @($acl.Access | Where-Object {
            $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            (($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
                [Security.AccessControl.FileSystemRights]::FullControl)
        } | ForEach-Object {
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        } | Sort-Object -Unique)
        $missingWriterSids = @($allowedWriterSids | Where-Object { $_ -notin $fullControlSids })
        if ($missingWriterSids) {
            throw "Android workspace ACL is missing required full-control identities at ${validatedTarget}: $($missingWriterSids -join ', ')"
        }

        $unauthorizedRules = @(Get-AndroidWorkspaceUnauthorizedWriteRules -Path $validatedTarget)
        if ($unauthorizedRules) {
            $identities = @($unauthorizedRules.IdentityReference.Value | Sort-Object -Unique)
            throw "Android workspace ACL grants write access to unauthorized identities at ${validatedTarget}: $($identities -join ', ')"
        }
    }

    Assert-AndroidWorkspaceToolIntegrity -WorkspaceRoot $WorkspaceRoot
    Assert-AndroidWorkspaceAdbIdentity -WorkspaceRoot $WorkspaceRoot
}

function Protect-AndroidWorkspaceSecurity {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    $targets = @(Get-AndroidWorkspaceSecurityTargets -WorkspaceRoot $WorkspaceRoot)
    foreach ($target in $targets) {
        $validatedTarget = Assert-AndroidWorkspaceSecurityTarget `
            -WorkspaceRoot $WorkspaceRoot `
            -Path $target
        $security = New-AndroidWorkspaceDirectorySecurity
        Set-Acl -LiteralPath $validatedTarget -AclObject $security
    }

    Assert-AndroidWorkspaceSecurity -WorkspaceRoot $WorkspaceRoot
}

function Get-AndroidWorkspaceSecuritySummary {
    param([Parameter(Mandatory)][string]$WorkspaceRoot)

    @(Get-AndroidWorkspaceSecurityTargets -WorkspaceRoot $WorkspaceRoot | ForEach-Object {
        $acl = Get-Acl -LiteralPath $_
        [pscustomobject]@{
            Path = $_
            Owner = $acl.Owner
            InheritanceProtected = $acl.AreAccessRulesProtected
            UnauthorizedWriteRuleCount = @(Get-AndroidWorkspaceUnauthorizedWriteRules -Path $_).Count
        }
    })
}
