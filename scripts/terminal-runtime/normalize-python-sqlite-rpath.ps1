[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PayloadRoot,
    [Parameter(Mandatory)]
    [string]$PatchelfTool,
    [string]$NdkRoot = $env:ANDROID_NDK_ROOT,
    [string]$DockerImage = 'node@sha256:7af03b14a13c8cdd38e45058fd957bf00a72bbe17feac43b1c15a689c029c732',
    [string]$ExpectedPatchelfSha256 = '7be0ed7d5865a78c581108a748fbe6a02be99b8fefc338cd8bee6a055040d251'
)

$ErrorActionPreference = 'Stop'

$androidAbis = @('arm64-v8a', 'x86_64')
$readElf = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readElf -PathType Leaf)) {
    throw "llvm-readelf.exe was not found: $readElf"
}

if (-not (Test-Path -LiteralPath $PatchelfTool -PathType Leaf)) {
    throw "patchelf tool was not found: $PatchelfTool"
}
$patchelfHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $PatchelfTool).Hash.ToLowerInvariant()
if ($patchelfHash -ne $ExpectedPatchelfSha256.ToLowerInvariant()) {
    throw "patchelf SHA-256 mismatch: expected $ExpectedPatchelfSha256, got $patchelfHash"
}

$payloadRootResolved = (Resolve-Path -LiteralPath $PayloadRoot).Path
$patchelfToolResolved = (Resolve-Path -LiteralPath $PatchelfTool).Path
$payloadMount = "type=bind,source=$payloadRootResolved,target=/payload"
$toolMount = "type=bind,source=$patchelfToolResolved,target=/tool/patchelf,readonly=true"

function Invoke-PatchelfContainer {
    param(
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string]$Description
    )

    $dockerArguments = @(
        'run', '--rm', '--entrypoint', '/bin/sh',
        '--mount', $payloadMount,
        '--mount', $toolMount,
        $DockerImage,
        '-c', $Command
    )
    $outputLines = @(& docker @dockerArguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output = ($outputLines | Out-String).Trim()
    if ($exitCode -ne 0) {
        throw "patchelf command failed for ${Description} with exit code ${exitCode}: $output"
    }
    return $output
}

function Get-UgkReadElfText {
    param([Parameter(Mandatory)][string]$Path)

    $outputLines = @(& $readElf -h -l -d -W $Path 2>&1)
    $exitCode = $LASTEXITCODE
    $output = ($outputLines | Out-String)
    if ($exitCode -ne 0) {
        throw "llvm-readelf failed for $Path with exit code ${exitCode}: $output"
    }
    return $output
}

function Get-UgkDependencies {
    param([Parameter(Mandatory)][string]$ElfText)

    return @(
        [regex]::Matches($ElfText, 'Shared library: \[([^\]]+)\]') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
}

function Get-UgkLoadAlignments {
    param([Parameter(Mandatory)][string]$ElfText)

    $matches = [regex]::Matches(
        $ElfText,
        '(?m)^\s*LOAD\s+.*?\sR(?:\s*E)?(?:\s*W)?\s+0x(?<align>[0-9a-fA-F]+)\s*$'
    )
    if ($matches.Count -eq 0) {
        throw 'ELF has no PT_LOAD program headers.'
    }
    $alignments = @()
    foreach ($match in $matches) {
        $value = [Convert]::ToInt64($match.Groups['align'].Value, 16)
        if ($value -lt 0x4000) {
            throw "ELF PT_LOAD alignment is below 16 KB: 0x$($match.Groups['align'].Value)"
        }
        if (($value -band ($value - 1)) -ne 0) {
            throw "ELF PT_LOAD alignment is not a power of two: 0x$($match.Groups['align'].Value)"
        }
        $alignments += $value
    }
    return ,$alignments
}

function Get-UgkElfSnapshot {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Abi
    )

    $elf = Get-UgkReadElfText -Path $Path
    $expectedMachine = if ($Abi -eq 'arm64-v8a') {
        'AArch64'
    } else {
        'Advanced Micro Devices X86-64'
    }
    if (-not ($elf -match '(?m)^\s*Class:\s+ELF64\s*$')) {
        throw "Expected ELF64: $Path"
    }
    if (-not ($elf -match '(?m)^\s*Type:\s+DYN \(Shared object file\)\s*$')) {
        throw "Expected ET_DYN: $Path"
    }
    if (-not ($elf -match "(?m)^\s*Machine:\s+$([regex]::Escape($expectedMachine))\s*$")) {
        throw "Unexpected ELF machine for $Abi`: $Path"
    }

    $alignments = Get-UgkLoadAlignments -ElfText $elf
    $stackMatch = [regex]::Match(
        $elf,
        '(?m)^\s*GNU_STACK\s+.*?\s(?<flags>R(?:\s*E)?(?:\s*W)?)\s+0x(?<align>[0-9a-fA-F]+)\s*$'
    )
    if (-not $stackMatch.Success) {
        throw "Missing GNU_STACK program header: $Path"
    }
    $stackFlags = $stackMatch.Groups['flags'].Value -replace '\s', ''
    if ($stackFlags.Contains('W') -and $stackFlags.Contains('E')) {
        throw "GNU_STACK is writable and executable ($stackFlags): $Path"
    }

    $dynamicPathLines = @(
        $elf -split '\r?\n' |
            Where-Object { $_ -match '\b(RPATH|RUNPATH)\b' }
    )
    $hasTextRel = $elf -match '(?im)\bTEXTREL\b'
    return [pscustomobject]@{
        ElfText = $elf
        Dependencies = @(Get-UgkDependencies -ElfText $elf)
        LoadAlignments = @($alignments)
        DynamicPathLines = @($dynamicPathLines)
        HasTextRel = $hasTextRel
        Machine = $expectedMachine
        FileSize = (Get-Item -LiteralPath $Path).Length
        Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    }
}

$version = Invoke-PatchelfContainer -Command '/tool/patchelf --version' -Description 'version probe'
if ($version -notmatch '^patchelf 0\.14\.3\s*$') {
    throw "Unexpected patchelf version: $version"
}
Write-Output "Using $version from $PatchelfTool (SHA-256 $patchelfHash)"
Write-Output "Using Docker image $DockerImage"

$states = @()
foreach ($abi in $androidAbis) {
    $relativePath = "$abi/libsqlite3_python.so"
    $path = Join-Path $payloadRootResolved $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required SQLite Python payload is missing for ${abi}: $path"
    }

    $snapshot = Get-UgkElfSnapshot -Path $path -Abi $abi
    if ($snapshot.HasTextRel) {
        throw "TEXTREL is present before normalization: $path"
    }
    $rpath = Invoke-PatchelfContainer `
        -Command "/tool/patchelf --print-rpath /payload/$relativePath" `
        -Description "$relativePath RPATH precondition"
    if ($rpath -ne '/usr/local/lib') {
        throw "Refusing to normalize $path because patchelf reports RUNPATH '$rpath', expected exactly '/usr/local/lib'."
    }
    $runpathLines = @($snapshot.DynamicPathLines | Where-Object { $_ -match '\bRUNPATH\b' })
    $rpathLines = @($snapshot.DynamicPathLines | Where-Object { $_ -match '\bRPATH\b' -and $_ -notmatch '\bRUNPATH\b' })
    if ($runpathLines.Count -ne 1 -or $runpathLines[0] -notmatch '\[\/usr/local/lib\]') {
        throw "Expected one RUNPATH [/usr/local/lib] before normalization: $path"
    }
    if ($rpathLines.Count -ne 0) {
        throw "Unexpected legacy RPATH before normalization: $path"
    }

    $states += [pscustomobject]@{
        Abi = $abi
        RelativePath = $relativePath
        Path = $path
        Before = $snapshot
    }
}

foreach ($state in $states) {
    $null = Invoke-PatchelfContainer `
        -Command "/tool/patchelf --remove-rpath /payload/$($state.RelativePath)" `
        -Description "$($state.RelativePath) RPATH removal"

    $afterRpath = Invoke-PatchelfContainer `
        -Command "/tool/patchelf --print-rpath /payload/$($state.RelativePath)" `
        -Description "$($state.RelativePath) RPATH postcondition"
    if (-not [string]::IsNullOrWhiteSpace($afterRpath)) {
        throw "RPATH/RUNPATH remains after normalization: $($state.Path): $afterRpath"
    }

    $after = Get-UgkElfSnapshot -Path $state.Path -Abi $state.Abi
    if ($after.DynamicPathLines.Count -ne 0) {
        throw "readelf still reports RPATH/RUNPATH after normalization: $($state.Path): $($after.DynamicPathLines -join ' | ')"
    }
    if ($after.HasTextRel) {
        throw "TEXTREL appeared after normalization: $($state.Path)"
    }
    if (($state.Before.Dependencies -join "`n") -ne ($after.Dependencies -join "`n")) {
        throw "DT_NEEDED changed during normalization: $($state.Path)"
    }
    if ($state.Before.Machine -ne $after.Machine) {
        throw "ELF ABI changed during normalization: $($state.Path)"
    }
    if (($state.Before.LoadAlignments -join ',') -ne ($after.LoadAlignments -join ',')) {
        throw "PT_LOAD alignment changed during normalization: $($state.Path)"
    }

    Write-Output (
        "Normalized {0}: size {1} -> {2}, SHA-256 {3} -> {4}, DT_NEEDED unchanged ({5})" -f
        $state.RelativePath,
        $state.Before.FileSize,
        $after.FileSize,
        $state.Before.Sha256,
        $after.Sha256,
        ($after.Dependencies -join ', ')
    )
}

Write-Output 'Python SQLite RPATH normalization passed for exactly two ABI payloads.'
