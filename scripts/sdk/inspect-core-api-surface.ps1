param(
    [string]$AarPath = '',
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($AarPath)) {
    $AarPath = Join-Path $repositoryRoot 'ugk-pi-android\build\outputs\aar\ugk-pi-android-release.aar'
}

$resolvedAarPath = (Resolve-Path -LiteralPath $AarPath).Path
$workParent = Join-Path $repositoryRoot 'build\sdk-api-surface'
$workRoot = Join-Path $workParent ([Guid]::NewGuid().ToString('N'))
$classesJarPath = Join-Path $workRoot 'classes.jar'

function Assert-Command {
    param(
        [Parameter(Mandatory)][string]$Name
    )

    if ($null -eq (Get-Command -Name $Name -ErrorAction SilentlyContinue)) {
        throw "Required JDK command is unavailable: $Name"
    }
}

function Remove-TemporaryWorkDir {
    if ($KeepWorkDir -or -not (Test-Path -LiteralPath $workRoot)) {
        return
    }

    $resolvedWorkRoot = (Resolve-Path -LiteralPath $workRoot).Path
    $resolvedWorkParent = (Resolve-Path -LiteralPath $workParent).Path
    $parentPrefix = $resolvedWorkParent.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedWorkRoot.StartsWith($parentPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a work directory outside the expected temporary parent: $resolvedWorkRoot"
    }

    Remove-Item -LiteralPath $resolvedWorkRoot -Recurse -Force
}

try {
    Assert-Command -Name 'jar'
    Assert-Command -Name 'javap'

    if (Test-Path -LiteralPath $workRoot) {
        throw "Temporary work directory unexpectedly exists: $workRoot"
    }
    New-Item -ItemType Directory -Path $workRoot | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $aar = [System.IO.Compression.ZipFile]::OpenRead($resolvedAarPath)
    try {
        $classesEntry = $aar.GetEntry('classes.jar')
        if ($null -eq $classesEntry) {
            throw "AAR is missing classes.jar: $resolvedAarPath"
        }

        $entryStream = $classesEntry.Open()
        try {
            $outputStream = [System.IO.File]::Open(
                $classesJarPath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            try {
                $entryStream.CopyTo($outputStream)
            } finally {
                $outputStream.Dispose()
            }
        } finally {
            $entryStream.Dispose()
        }
    } finally {
        $aar.Dispose()
    }

    $classEntries = @(& jar tf $classesJarPath 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect classes.jar with jar (exit code $LASTEXITCODE)."
    }

    $classNames = @(
        $classEntries |
            Where-Object {
                $_ -like '*.class' -and
                    $_ -notlike 'META-INF/*' -and
                    $_ -ne 'module-info.class'
            } |
            ForEach-Object {
                $_.Substring(0, $_.Length - 6).Replace('/', '.')
            }
    )
    if ($classNames.Count -eq 0) {
        throw 'classes.jar does not contain any inspectable class files.'
    }

    $javapOutput = @(& javap -public -classpath $classesJarPath $classNames 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect public signatures with javap (exit code $LASTEXITCODE)."
    }

    $publicTypeLines = @(
        $javapOutput | Where-Object {
            $_ -match '^public (?:final |abstract )?(?:class|interface|enum) '
        }
    )
    $publicTypeNames = @(
        foreach ($line in $publicTypeLines) {
            if ($line -match '^public (?:final |abstract )?(?:class|interface|enum) ([^ ]+)') {
                $Matches[1]
            }
        }
    )
    $publicMemberLines = @($javapOutput | Where-Object { $_ -match '^  public ' })
    $topLevelClassNames = @($classNames | Where-Object { $_ -notmatch '\$' })
    $sourceFacingTypeNames = @(
        $publicTypeNames | Where-Object {
            $_ -notmatch '\$DefaultImpls$' -and $_ -notmatch 'AgentRuntimeKt$'
        }
    )

    $generatedMemberCounts = [ordered]@{
        'access$ helpers' = @($publicMemberLines | Where-Object { $_ -match 'access\$' }).Count
        'copy$default overloads' = @($publicMemberLines | Where-Object { $_ -match 'copy\$default' }).Count
        'componentN methods' = @($publicMemberLines | Where-Object { $_ -match '\bcomponent\d+\(' }).Count
        'DefaultConstructorMarker overloads' = @($publicMemberLines | Where-Object { $_ -match 'DefaultConstructorMarker' }).Count
    }

    Write-Output 'Core API surface inventory completed.'
    Write-Output "AAR: $resolvedAarPath"
    Write-Output "Class files: $($classNames.Count)"
    Write-Output "Top-level class files: $($topLevelClassNames.Count)"
    Write-Output "Public type declarations (javap): $($publicTypeNames.Count)"
    Write-Output "Source-facing public types (excluding Kotlin facades/DefaultImpls): $($sourceFacingTypeNames.Count)"
    Write-Output "Public member signatures (javap): $($publicMemberLines.Count)"
    Write-Output 'Compiler-generated public member heuristics:'
    foreach ($name in $generatedMemberCounts.Keys) {
        Write-Output "  ${name}: $($generatedMemberCounts[$name])"
    }
    Write-Output 'Source-facing public types:'
    $sourceFacingTypeNames | Sort-Object | ForEach-Object { Write-Output "  $_" }
    if ($KeepWorkDir) {
        Write-Output "Kept temporary work directory: $workRoot"
    }
} finally {
    Remove-TemporaryWorkDir
}
