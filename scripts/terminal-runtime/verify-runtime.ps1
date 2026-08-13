[CmdletBinding()]
param(
    [string]$NdkRoot = $env:ANDROID_NDK_ROOT,
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [switch]$CheckPackages,
    [string]$ReleaseAar,
    [string[]]$ProbeApk,
    [string]$Zipalign
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    throw 'Set ANDROID_NDK_ROOT or pass -NdkRoot.'
}

$readElf = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $readElf)) {
    throw "llvm-readelf.exe was not found: $readElf"
}

$moduleRoot = Join-Path $RepositoryRoot 'ugk-terminal-runtime-android'
$lockPath = Join-Path $moduleRoot 'runtime-lock.json'
$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
$androidAbis = @('arm64-v8a', 'x86_64')
$runtimeBridgeNames = @(
    'libugk_python.so',
    'libugk_runtime_probe.so',
    'libugk_session_launcher.so',
    'libugk_terminal_native.so'
)
$androidSystemLibraries = @(
    'libandroid.so',
    'libaaudio.so',
    'libc.so',
    'libdl.so',
    'libEGL.so',
    'libGLESv1_CM.so',
    'libGLESv2.so',
    'libGLESv3.so',
    'libjnigraphics.so',
    'liblog.so',
    'libm.so',
    'libmediandk.so',
    'libOpenSLES.so',
    'libz.so'
)
$forbiddenStrings = @(
    $lock.forbiddenStrings |
        ForEach-Object { [string]$_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
)

function Get-UgkSha256FromStream {
    param([Parameter(Mandatory)][System.IO.Stream]$Stream)

    $digest = [System.Security.Cryptography.SHA256]::Create()
    try {
        $buffer = New-Object byte[] 16384
        while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $null = $digest.TransformBlock($buffer, 0, $read, $buffer, 0)
        }
        $null = $digest.TransformFinalBlock($buffer, 0, 0)
        return ([System.BitConverter]::ToString($digest.Hash)).Replace('-', '').ToLowerInvariant()
    } finally {
        $digest.Dispose()
    }
}

function Get-UgkPythonExtensionTreeSha256 {
    param([Parameter(Mandatory)][System.IO.FileInfo[]]$Files)

    $lines = $Files |
        Sort-Object Name |
        ForEach-Object {
            $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
            "$($_.Name)`t$($_.Length)`t$sha256"
        }
    $text = ($lines -join "`n") + "`n"
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $stream = New-Object System.IO.MemoryStream (, $bytes)
    try {
        return Get-UgkSha256FromStream -Stream $stream
    } finally {
        $stream.Dispose()
    }
}

function Get-UgkElfDependencies {
    param([Parameter(Mandatory)][string]$ElfText)

    return @(
        [regex]::Matches($ElfText, 'Shared library: \[([^\]]+)\]') |
            ForEach-Object { $_.Groups[1].Value } |
            Sort-Object -Unique
    )
}

function Get-UgkElfText {
    param([Parameter(Mandatory)][string]$PayloadPath)

    $elfText = (& $readElf -h -l -d -W $PayloadPath 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "llvm-readelf failed for $PayloadPath with exit code ${exitCode}: $elfText"
    }
    return $elfText
}

function Get-UgkElfMachine {
    param([Parameter(Mandatory)][string]$Abi)

    if ($Abi -eq 'arm64-v8a') {
        return 'AArch64'
    }
    if ($Abi -eq 'x86_64') {
        return 'Advanced Micro Devices X86-64'
    }
    throw "Unsupported ABI: $Abi"
}

function Assert-UgkElfStaticProperties {
    param(
        [Parameter(Mandatory)][string]$PayloadPath,
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)][string]$Description,
        [switch]$RequireInterpreter
    )

    $elf = Get-UgkElfText -PayloadPath $PayloadPath
    $machine = Get-UgkElfMachine -Abi $Abi
    if (-not ($elf -match '(?m)^\s*Class:\s+ELF64\s*$')) {
        throw "ELF check failed for ${Description}: expected ELF64"
    }
    if (-not ($elf -match '(?m)^\s*Type:\s+DYN \(Shared object file\)\s*$')) {
        throw "ELF check failed for ${Description}: expected ET_DYN"
    }
    if (-not ($elf -match "(?m)^\s*Machine:\s+$([regex]::Escape($machine))\s*$")) {
        throw "ELF check failed for ${Description}: expected machine $machine"
    }

    $loadMatches = [regex]::Matches(
        $elf,
        '(?m)^\s*LOAD\s+.*?\s(?<flags>R(?:\s*E)?(?:\s*W)?)\s+0x(?<align>[0-9a-fA-F]+)\s*$'
    )
    if ($loadMatches.Count -eq 0) {
        throw "ELF check failed for ${Description}: no PT_LOAD program headers"
    }
    foreach ($loadMatch in $loadMatches) {
        $align = [Convert]::ToInt64($loadMatch.Groups['align'].Value, 16)
        if ($align -lt 0x4000) {
            throw "ELF 16 KB check failed for ${Description}: PT_LOAD p_align=0x$($loadMatch.Groups['align'].Value), expected at least 0x4000"
        }
        if (($align -band ($align - 1)) -ne 0) {
            throw "ELF check failed for ${Description}: PT_LOAD p_align is not a power of two: 0x$($loadMatch.Groups['align'].Value)"
        }
    }

    $stackMatch = [regex]::Match(
        $elf,
        '(?m)^\s*GNU_STACK\s+.*?\s(?<flags>R(?:\s*E)?(?:\s*W)?)\s+0x(?<align>[0-9a-fA-F]+)\s*$'
    )
    if (-not $stackMatch.Success) {
        throw "ELF security check failed for ${Description}: missing GNU_STACK program header"
    }
    $stackFlags = $stackMatch.Groups['flags'].Value -replace '\s', ''
    if ($stackFlags.Contains('E') -and $stackFlags.Contains('W')) {
        throw "ELF security check failed for ${Description}: GNU_STACK is writable and executable ($stackFlags)"
    }

    $dynamicPathLines = @(
        $elf -split '\r?\n' |
            Where-Object { $_ -match '\b(RPATH|RUNPATH)\b' }
    )
    if ($dynamicPathLines.Count -gt 0) {
        throw "ELF security check failed for ${Description}: RPATH/RUNPATH is present: $($dynamicPathLines -join ' | ')"
    }
    if ($elf -match '(?im)\bTEXTREL\b') {
        throw "ELF security check failed for ${Description}: TEXTREL is present"
    }
    if ($RequireInterpreter -and -not ($elf -match '\[Requesting program interpreter: /system/bin/linker64\]')) {
        throw "ELF check failed for ${Description}: expected /system/bin/linker64 interpreter"
    }
    return $elf
}

function Assert-UgkSharedObject {
    param(
        [Parameter(Mandatory)][string]$PayloadPath,
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)][string]$Description,
        [switch]$RequireInterpreter
    )

    return Assert-UgkElfStaticProperties -PayloadPath $PayloadPath -Abi $Abi -Description $Description -RequireInterpreter:$RequireInterpreter
}

function Assert-UgkDynamicDependencyClosure {
    param(
        [Parameter(Mandatory)][string]$ElfText,
        [Parameter(Mandatory)][string]$Description,
        [string[]]$LocalLibraryNames = @()
    )

    $actualDependencies = Get-UgkElfDependencies -ElfText $ElfText
    $allowedDependencies = @($androidSystemLibraries + $LocalLibraryNames) | Sort-Object -Unique
    $unexpectedDependencies = @(
        $actualDependencies |
            Where-Object { $_ -notin $allowedDependencies }
    )
    if ($unexpectedDependencies.Count -gt 0) {
        $hostDependency = $unexpectedDependencies | Where-Object {
            $_ -match '\.so\.\d+$|^ld-linux|^libstdc\+\+|^libgcc_s|^libpthread'
        } | Select-Object -First 1
        if ($null -ne $hostDependency) {
            throw "DT_NEEDED host/Linux dependency for ${Description}: $hostDependency"
        }
        throw "DT_NEEDED dependency closure failed for ${Description}: $($unexpectedDependencies -join ', ')"
    }
    return $actualDependencies
}

function Assert-UgkNoForbiddenStrings {
    param(
        [Parameter(Mandatory)][string]$PayloadPath,
        [Parameter(Mandatory)][string]$Description
    )

    if ($forbiddenStrings.Count -eq 0) {
        return
    }
    $payloadAscii = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($PayloadPath))
    foreach ($forbiddenString in $forbiddenStrings) {
        if ($payloadAscii.Contains($forbiddenString)) {
            throw "Forbidden fixed path or package marker '$forbiddenString' was found in $Description"
        }
    }
}

function Get-UgkSourceNativeFilesByAbi {
    $filesByAbi = @{}
    foreach ($abi in $androidAbis) {
        $directory = Join-Path $moduleRoot "src/main/jniLibs/$abi"
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
            throw "Native payload directory is missing for ${abi}: $directory"
        }
        $files = @(Get-ChildItem -LiteralPath $directory -File)
        $nonSoFiles = @($files | Where-Object { $_.Extension -ne '.so' })
        if ($nonSoFiles.Count -gt 0) {
            throw "Unexpected non-ELF payload files for ${abi}: $($nonSoFiles.Name -join ', ')"
        }
        if ($files.Count -eq 0) {
            throw "Native payload directory is empty for ${abi}: $directory"
        }
        $filesByAbi[$abi] = $files
    }
    return ,$filesByAbi
}

function Get-UgkModuleRelativePath {
    param([Parameter(Mandatory)][string]$Path)

    return (($Path.Substring($moduleRoot.Length) -replace '^[\\/]+', '') -replace '\\', '/')
}

function Assert-UgkLockNativeInventory {
    param(
        [Parameter(Mandatory)][hashtable]$SourceNativeFilesByAbi
    )

    $expectedPaths = @(
        $lock.artifacts | ForEach-Object { ([string]$_.path -replace '\\', '/') }
    )
    if ($null -ne $lock.pythonRuntime) {
        $expectedPaths += @(
            $lock.pythonRuntime.nativeLibraries |
                ForEach-Object { ([string]$_.path -replace '\\', '/') }
        )
        foreach ($extensionSet in @($lock.pythonRuntime.extensionSets)) {
            $extensionDirectory = Join-Path $moduleRoot $extensionSet.directory
            $extensionFiles = @(Get-ChildItem -LiteralPath $extensionDirectory -File -Filter '*.so')
            $expectedPaths += @(
                $extensionFiles | ForEach-Object { Get-UgkModuleRelativePath -Path $_.FullName }
            )
        }
    }

    $actualPaths = @()
    foreach ($abi in $androidAbis) {
        $actualPaths += @(
            $SourceNativeFilesByAbi[$abi] |
                ForEach-Object { Get-UgkModuleRelativePath -Path $_.FullName }
        )
    }
    $expectedPaths = @($expectedPaths | Sort-Object -Unique)
    $actualPaths = @($actualPaths | Sort-Object -Unique)
    $inventoryDiff = @(Compare-Object -ReferenceObject $expectedPaths -DifferenceObject $actualPaths)
    if ($inventoryDiff.Count -gt 0) {
        $details = $inventoryDiff | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
        throw "runtime-lock/native payload inventory mismatch: $($details -join '; ')"
    }

    foreach ($abi in $androidAbis) {
        Write-Output "Verified native payload inventory ${abi}: $($SourceNativeFilesByAbi[$abi].Count) files"
    }
}

function Get-UgkReleaseStrippedNativeFile {
    param(
        [Parameter(Mandatory)][string]$Abi,
        [Parameter(Mandatory)][string]$Name
    )

    $strippedRoot = Join-Path $moduleRoot 'build/intermediates/stripped_native_libs/release'
    if (-not (Test-Path -LiteralPath $strippedRoot -PathType Container)) {
        throw "Release stripped native output is missing; run :ugk-terminal-runtime-android:assembleRelease first: $strippedRoot"
    }
    $candidates = @(
        Get-ChildItem -LiteralPath $strippedRoot -Recurse -File -Filter $Name |
            Where-Object { $_.Directory.Name -eq $Abi }
    )
    if ($candidates.Count -eq 0) {
        throw "Release stripped native output is missing for $Abi/$Name under $strippedRoot"
    }
    $candidateHashes = @(
        $candidates |
            ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant() } |
            Sort-Object -Unique
    )
    if ($candidateHashes.Count -gt 1) {
        throw "Ambiguous release stripped native output for ${Abi}/${Name}: multiple hashes found"
    }
    return ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

function Get-UgkExpectedReleaseNativeFiles {
    param(
        [Parameter(Mandatory)][hashtable]$SourceNativeFilesByAbi
    )

    $filesByAbi = @{}
    foreach ($abi in $androidAbis) {
        $files = @{}
        foreach ($file in $SourceNativeFilesByAbi[$abi]) {
            $files[$file.Name] = $file.FullName
        }
        foreach ($name in $runtimeBridgeNames) {
            $files[$name] = Get-UgkReleaseStrippedNativeFile -Abi $abi -Name $name
        }
        if ($files.Count -ne ($SourceNativeFilesByAbi[$abi].Count + $runtimeBridgeNames.Count)) {
            throw "Release native inventory count mismatch for $abi"
        }
        $filesByAbi[$abi] = $files
    }
    return ,$filesByAbi
}

function Get-UgkZipEntrySha256 {
    param([Parameter(Mandatory)][System.IO.Compression.ZipArchiveEntry]$Entry)

    $stream = $Entry.Open()
    try {
        return Get-UgkSha256FromStream -Stream $stream
    } finally {
        $stream.Dispose()
    }
}

function Get-UgkZipalignPath {
    if (-not [string]::IsNullOrWhiteSpace($Zipalign)) {
        if (-not (Test-Path -LiteralPath $Zipalign -PathType Leaf)) {
            throw "zipalign.exe was not found: $Zipalign"
        }
        return (Resolve-Path -LiteralPath $Zipalign).Path
    }

    $sdkRoot = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
        $sdkRoot = $env:ANDROID_HOME
    }
    if (-not [string]::IsNullOrWhiteSpace($sdkRoot)) {
        $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
        if (Test-Path -LiteralPath $buildToolsRoot -PathType Container) {
            $candidates = @(
                Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName 'zipalign.exe' } |
                    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
            )
            if ($candidates.Count -gt 0) {
                return $candidates[0]
            }
        }
    }

    $command = Get-Command 'zipalign.exe' -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    return $null
}

function Assert-UgkZipNativePackage {
    param(
        [Parameter(Mandatory)][string]$PackagePath,
        [Parameter(Mandatory)][ValidateSet('aar', 'apk')][string]$PackageKind,
        [Parameter(Mandatory)][hashtable]$ExpectedFilesByAbi,
        [string]$ZipalignPath
    )

    if (-not (Test-Path -LiteralPath $PackagePath -PathType Leaf)) {
        throw "Release package is missing: $PackagePath"
    }
    $packagePathResolved = (Resolve-Path -LiteralPath $PackagePath).Path
    $entryPrefix = if ($PackageKind -eq 'aar') { 'jni' } else { 'lib' }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($packagePathResolved)
    try {
        $nativeEntries = @(
            $zip.Entries | Where-Object { $_.FullName.EndsWith('.so') }
        )
        $expectedEntryNames = @()
        foreach ($abi in $androidAbis) {
            foreach ($name in $ExpectedFilesByAbi[$abi].Keys) {
                $expectedEntryNames += "$entryPrefix/$abi/$name"
            }
        }
        $actualEntryNames = @($nativeEntries | ForEach-Object { $_.FullName } | Sort-Object)
        $expectedEntryNames = @($expectedEntryNames | Sort-Object)
        $entryDiff = @(Compare-Object -ReferenceObject $expectedEntryNames -DifferenceObject $actualEntryNames)
        if ($entryDiff.Count -gt 0) {
            $details = $entryDiff | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
            throw "${PackageKind} native entry inventory mismatch in ${packagePathResolved}: $($details -join '; ')"
        }

        foreach ($abi in $androidAbis) {
            foreach ($name in $ExpectedFilesByAbi[$abi].Keys) {
                $entryName = "$entryPrefix/$abi/$name"
                $entry = $zip.GetEntry($entryName)
                if ($null -eq $entry) {
                    throw "Missing ${PackageKind} native entry: $entryName in $packagePathResolved"
                }
                $expectedPath = $ExpectedFilesByAbi[$abi][$name]
                $expectedFile = Get-Item -LiteralPath $expectedPath
                if ($entry.Length -ne $expectedFile.Length) {
                    throw "${PackageKind} native size mismatch for ${entryName}: expected $($expectedFile.Length), got $($entry.Length)"
                }
                $expectedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $expectedPath).Hash.ToLowerInvariant()
                $actualHash = Get-UgkZipEntrySha256 -Entry $entry
                if ($actualHash -ne $expectedHash) {
                    throw "${PackageKind} native SHA-256 mismatch for ${entryName}: expected $expectedHash, got $actualHash"
                }
            }
        }

        $uncompressedNative = @($nativeEntries | Where-Object { $_.CompressedLength -eq $_.Length })
        $compressedNative = @($nativeEntries | Where-Object { $_.CompressedLength -lt $_.Length })
        Write-Output "Verified $PackageKind native package ${packagePathResolved}: $($nativeEntries.Count) entries; compressed=$($compressedNative.Count), uncompressed=$($uncompressedNative.Count)"
        if ($uncompressedNative.Count -eq 0) {
            Write-Output "[INFO] $PackageKind native entries are compressed; APK ZIP 16 KB page-offset alignment is not applicable to these entries"
        }
    } finally {
        $zip.Dispose()
    }

    if ([string]::IsNullOrWhiteSpace($ZipalignPath)) {
        Write-Output "[WARN] zipalign.exe was not found; ZIP alignment check skipped for $packagePathResolved"
        return
    }
    $zipalignOutput = @()
    $zipalignExitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $zipalignOutput = @(& $ZipalignPath -c -P 16 -v 4 $packagePathResolved 2>&1)
        $zipalignExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($zipalignExitCode -ne 0) {
        $tail = ($zipalignOutput | Select-Object -Last 12) -join "`n"
        throw "zipalign 16 KB check failed for ${packagePathResolved} with exit code ${zipalignExitCode}: $tail"
    }
    Write-Output "Verified ZIP alignment with zipalign -P 16: $packagePathResolved"
}

function Assert-UgkNoNodeCoreMapping {
    param(
        [Parameter(Mandatory)][hashtable]$SourceNativeFilesByAbi
    )

    $nodePayloads = @()
    foreach ($abi in $androidAbis) {
        $nodePayloads += @($SourceNativeFilesByAbi[$abi] | Where-Object { $_.Name -match '(?i)node' })
    }
    if ($nodePayloads.Count -gt 0) {
        throw "v1 Core contains Node payload names: $($nodePayloads.Name -join ', ')"
    }
    $nodeCommands = @($lock.artifacts | Where-Object { ([string]$_.command) -match '(?i)node' })
    if ($nodeCommands.Count -gt 0) {
        throw 'v1 Core runtime-lock contains a Node command mapping.'
    }
    $coreMappingFiles = @(
        (Join-Path $moduleRoot 'src/main/java/com/ugk/pi/terminal/runtime/BashRuntime.kt'),
        (Join-Path $RepositoryRoot 'pi-terminal-skill-android/src/main/java/com/ugk/pi/terminal/skill/BashCommandTool.kt'),
        $lockPath
    )
    $forbiddenNodeMappings = @('libugk_node\.so', 'nodeExecutableFileName', 'NODE_EXTRA_CA_CERTS', 'NODE_OPTIONS')
    foreach ($path in $coreMappingFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            continue
        }
        $text = Get-Content -LiteralPath $path -Raw
        foreach ($pattern in $forbiddenNodeMappings) {
            if ($text -match $pattern) {
                throw "v1 Core Node-specific mapping '$pattern' found in $path"
            }
        }
    }
    Write-Output 'Verified v1 Core contains no Node payload or Node-specific mapping.'
}

$sourceNativeFilesByAbi = Get-UgkSourceNativeFilesByAbi

foreach ($artifact in $lock.artifacts) {
    if ([string]::IsNullOrWhiteSpace($artifact.command)) {
        throw "Runtime lock artifact is missing command: $($artifact.path)"
    }
    $expectedDependencies = @($artifact.requiredDynamicDependencies | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    if ($expectedDependencies.Count -eq 0) {
        throw "Runtime lock artifact is missing requiredDynamicDependencies: $($artifact.path)"
    }
    $payloadPath = Join-Path $moduleRoot $artifact.path
    if (-not (Test-Path -LiteralPath $payloadPath)) {
        throw "Payload is missing: $payloadPath"
    }

    $file = Get-Item -LiteralPath $payloadPath
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $payloadPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $artifact.sha256) {
        throw "SHA-256 mismatch for $($artifact.abi): expected $($artifact.sha256), got $actualHash"
    }
    if ($file.Length -ne [long]$artifact.sizeBytes) {
        throw "Size mismatch for $($artifact.abi): expected $($artifact.sizeBytes), got $($file.Length)"
    }
    if ($forbiddenStrings.Count -gt 0) {
        $payloadAscii = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($payloadPath))
        foreach ($forbiddenString in $forbiddenStrings) {
            if ($payloadAscii.Contains($forbiddenString)) {
                throw "Forbidden fixed path or package marker '$forbiddenString' was found in $($artifact.command) $($artifact.abi)"
            }
        }
    }

    $elf = Assert-UgkSharedObject -PayloadPath $payloadPath -Abi $artifact.abi -Description "$($artifact.command) $($artifact.abi)" -RequireInterpreter

    $actualDependencies = Assert-UgkDynamicDependencyClosure -ElfText $elf -Description "$($artifact.command) $($artifact.abi)" -LocalLibraryNames @()
    if (Compare-Object -ReferenceObject $expectedDependencies -DifferenceObject $actualDependencies) {
        throw "Unexpected dynamic dependencies for $($artifact.command) $($artifact.abi): $($actualDependencies -join ', ')"
    }

    Write-Output "Verified $($artifact.command) $($artifact.abi): $($artifact.sha256)"
}

if ($null -ne $lock.dataArtifacts) {
    foreach ($dataArtifact in $lock.dataArtifacts) {
        if ([string]::IsNullOrWhiteSpace($dataArtifact.name)) {
            throw "Runtime lock data artifact is missing a name: $($dataArtifact.path)"
        }
        $dataPath = Join-Path $moduleRoot $dataArtifact.path
        if (-not (Test-Path -LiteralPath $dataPath)) {
            throw "Data artifact is missing: $dataPath"
        }

        $dataFile = Get-Item -LiteralPath $dataPath
        $actualDataHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $dataPath).Hash.ToLowerInvariant()
        if ($actualDataHash -ne $dataArtifact.sha256) {
            throw "SHA-256 mismatch for data artifact $($dataArtifact.name): expected $($dataArtifact.sha256), got $actualDataHash"
        }
        if ($dataFile.Length -ne [long]$dataArtifact.sizeBytes) {
            throw "Size mismatch for data artifact $($dataArtifact.name): expected $($dataArtifact.sizeBytes), got $($dataFile.Length)"
        }

        Write-Output "Verified data artifact $($dataArtifact.name): $($dataArtifact.sha256)"
    }
}

if ($null -ne $lock.pythonRuntime) {
    $pythonRuntime = $lock.pythonRuntime
    $extensionPrefix = [string]$pythonRuntime.extensionFilePrefix
    if ([string]::IsNullOrWhiteSpace($extensionPrefix)) {
        throw 'Python Runtime lock is missing extensionFilePrefix.'
    }

    foreach ($nativeLibrary in @($pythonRuntime.nativeLibraries)) {
        $payloadPath = Join-Path $moduleRoot $nativeLibrary.path
        if (-not (Test-Path -LiteralPath $payloadPath -PathType Leaf)) {
            throw "Python native library is missing: $payloadPath"
        }
        $file = Get-Item -LiteralPath $payloadPath
        $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $payloadPath).Hash.ToLowerInvariant()
        if ($actualHash -ne $nativeLibrary.sha256) {
            throw "SHA-256 mismatch for Python native library $($nativeLibrary.path): expected $($nativeLibrary.sha256), got $actualHash"
        }
        if ($file.Length -ne [long]$nativeLibrary.sizeBytes) {
            throw "Size mismatch for Python native library $($nativeLibrary.path): expected $($nativeLibrary.sizeBytes), got $($file.Length)"
        }

        Assert-UgkNoForbiddenStrings -PayloadPath $payloadPath -Description "Python native library $($nativeLibrary.path)"
        $elf = Assert-UgkSharedObject -PayloadPath $payloadPath -Abi $nativeLibrary.abi -Description "Python native library $($nativeLibrary.path)"
        $expectedDependencies = @($nativeLibrary.requiredDynamicDependencies | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $actualDependencies = Assert-UgkDynamicDependencyClosure -ElfText $elf -Description "Python native library $($nativeLibrary.path)" -LocalLibraryNames $expectedDependencies
        if (@(Compare-Object -ReferenceObject $expectedDependencies -DifferenceObject $actualDependencies).Count -gt 0) {
            throw "Unexpected dynamic dependencies for Python native library $($nativeLibrary.path): $($actualDependencies -join ', ')"
        }
        Write-Output "Verified Python native library $($nativeLibrary.abi) $($nativeLibrary.path): $actualHash"
    }

    foreach ($extensionSet in @($pythonRuntime.extensionSets)) {
        $extensionDirectory = Join-Path $moduleRoot $extensionSet.directory
        if (-not (Test-Path -LiteralPath $extensionDirectory -PathType Container)) {
            throw "Python extension directory is missing: $extensionDirectory"
        }
        $extensions = @(
            Get-ChildItem -LiteralPath $extensionDirectory -File |
                Where-Object { $_.Name -like "$extensionPrefix*.so" } |
                Sort-Object Name
        )
        if ($extensions.Count -ne [int]$extensionSet.fileCount) {
            throw "Python extension count mismatch for $($extensionSet.abi): expected $($extensionSet.fileCount), got $($extensions.Count)"
        }
        $extensionBytes = ($extensions | Measure-Object Length -Sum).Sum
        if ($extensionBytes -ne [long]$extensionSet.sizeBytes) {
            throw "Python extension size mismatch for $($extensionSet.abi): expected $($extensionSet.sizeBytes), got $extensionBytes"
        }
        $treeHash = Get-UgkPythonExtensionTreeSha256 -Files $extensions
        if ($treeHash -ne $extensionSet.treeSha256) {
            throw "Python extension tree SHA-256 mismatch for $($extensionSet.abi): expected $($extensionSet.treeSha256), got $treeHash"
        }

        $bareExtensions = @(
            Get-ChildItem -LiteralPath $extensionDirectory -Filter '*.cpython-314-*-linux-android.so' -File |
                Where-Object { -not $_.Name.StartsWith($extensionPrefix) }
        )
        if ($bareExtensions.Count -gt 0) {
            throw "Python extensions would not be extracted into nativeLibraryDir: $($bareExtensions.Name -join ', ')"
        }

        $allowedDependencies = @($extensionSet.allowedDynamicDependencies | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        foreach ($extension in $extensions) {
            Assert-UgkNoForbiddenStrings -PayloadPath $extension.FullName -Description "Python extension $($extension.Name)"
            $elf = Assert-UgkSharedObject -PayloadPath $extension.FullName -Abi $extensionSet.abi -Description "Python extension $($extension.Name)"
            $actualDependencies = Assert-UgkDynamicDependencyClosure -ElfText $elf -Description "Python extension $($extension.Name)" -LocalLibraryNames $allowedDependencies
            $unexpectedDependencies = @($actualDependencies | Where-Object { $_ -notin $allowedDependencies })
            if ($unexpectedDependencies.Count -gt 0) {
                throw "Unexpected dynamic dependencies for Python extension $($extension.Name): $($unexpectedDependencies -join ', ')"
            }
        }
        Write-Output "Verified Python extensions $($extensionSet.abi): $($extensions.Count) files, $treeHash"
    }

    $standardLibrary = $pythonRuntime.standardLibrary
    $manifestPath = Join-Path $moduleRoot $standardLibrary.manifestPath
    $archivePath = Join-Path $moduleRoot $standardLibrary.archivePath
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Python standard-library manifest is missing: $manifestPath"
    }
    if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
        throw "Python standard-library archive is missing: $archivePath"
    }

    $manifestEntries = @{}
    foreach ($line in Get-Content -LiteralPath $manifestPath) {
        $match = [regex]::Match($line, '^([0-9a-f]{64})  (.+)$')
        if (-not $match.Success) {
            throw "Malformed Python standard-library manifest entry: $line"
        }
        $relativePath = $match.Groups[2].Value
        $segments = $relativePath.Split('/')
        if (
            $relativePath.StartsWith('/') -or
            $relativePath.Contains('\') -or
            @($segments | Where-Object { [string]::IsNullOrWhiteSpace($_) -or $_ -eq '.' -or $_ -eq '..' }).Count -gt 0
        ) {
            throw "Unsafe Python standard-library manifest path: $relativePath"
        }
        if ($manifestEntries.ContainsKey($relativePath)) {
            throw "Duplicate Python standard-library manifest path: $relativePath"
        }
        $manifestEntries[$relativePath] = $match.Groups[1].Value
    }
    if ($manifestEntries.Count -ne [int]$standardLibrary.manifestEntryCount) {
        throw "Python standard-library manifest count mismatch: expected $($standardLibrary.manifestEntryCount), got $($manifestEntries.Count)"
    }

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $archiveEntries = @{}
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) {
                throw "Python standard-library archive contains a directory entry: $($entry.FullName)"
            }
            if (-not $manifestEntries.ContainsKey($entry.FullName)) {
                throw "Python standard-library archive contains an unexpected entry: $($entry.FullName)"
            }
            if ($archiveEntries.ContainsKey($entry.FullName)) {
                throw "Python standard-library archive contains a duplicate entry: $($entry.FullName)"
            }
            $stream = $entry.Open()
            try {
                $actualHash = Get-UgkSha256FromStream -Stream $stream
            } finally {
                $stream.Dispose()
            }
            if ($actualHash -ne $manifestEntries[$entry.FullName]) {
                throw "Python standard-library archive hash mismatch for $($entry.FullName): expected $($manifestEntries[$entry.FullName]), got $actualHash"
            }
            $archiveEntries[$entry.FullName] = $true
        }
        if ($archiveEntries.Count -ne $manifestEntries.Count) {
            throw "Python standard-library archive entry count mismatch: expected $($manifestEntries.Count), got $($archiveEntries.Count)"
        }
        foreach ($relativePath in $manifestEntries.Keys) {
            if (-not $archiveEntries.ContainsKey($relativePath)) {
                throw "Python standard-library archive is missing manifest entry: $relativePath"
            }
        }
        foreach ($requiredEntry in @($standardLibrary.requiredEntries | ForEach-Object { [string]$_ })) {
            if (-not $archiveEntries.ContainsKey($requiredEntry)) {
                throw "Python standard-library archive is missing required entry: $requiredEntry"
            }
        }
    } finally {
        $archive.Dispose()
    }
    Write-Output "Verified Python standard library: $($manifestEntries.Count) entries"
}

Assert-UgkLockNativeInventory -SourceNativeFilesByAbi $sourceNativeFilesByAbi
Assert-UgkNoNodeCoreMapping -SourceNativeFilesByAbi $sourceNativeFilesByAbi

if ($CheckPackages) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    if ([string]::IsNullOrWhiteSpace($ReleaseAar)) {
        $ReleaseAar = Join-Path $moduleRoot 'build/outputs/aar/ugk-terminal-runtime-android-release.aar'
    }
    if ($null -eq $ProbeApk -or $ProbeApk.Count -eq 0) {
        $ProbeApk = @(
            (Join-Path $RepositoryRoot 'terminal-probe-demo-a/build/outputs/apk/release/terminal-probe-demo-a-release-unsigned.apk'),
            (Join-Path $RepositoryRoot 'terminal-probe-demo-b/build/outputs/apk/release/terminal-probe-demo-b-release-unsigned.apk')
        )
    }

    $expectedReleaseNativeFiles = Get-UgkExpectedReleaseNativeFiles -SourceNativeFilesByAbi $sourceNativeFilesByAbi
    foreach ($abi in $androidAbis) {
        $localLibraryNames = @($expectedReleaseNativeFiles[$abi].Keys)
        foreach ($name in $runtimeBridgeNames) {
            $bridgePath = $expectedReleaseNativeFiles[$abi][$name]
            $requiresInterpreter = $name -in @('libugk_python.so', 'libugk_runtime_probe.so', 'libugk_session_launcher.so')
            $bridgeElf = Assert-UgkSharedObject -PayloadPath $bridgePath -Abi $abi -Description "CMake bridge $abi/$name" -RequireInterpreter:$requiresInterpreter
            $null = Assert-UgkDynamicDependencyClosure -ElfText $bridgeElf -Description "CMake bridge $abi/$name" -LocalLibraryNames $localLibraryNames
            Assert-UgkNoForbiddenStrings -PayloadPath $bridgePath -Description "CMake bridge $abi/$name"
        }
        Write-Output "Verified CMake bridge ELF set ${abi}: $($runtimeBridgeNames.Count) files"
    }

    $zipalignPath = Get-UgkZipalignPath
    Assert-UgkZipNativePackage -PackagePath $ReleaseAar -PackageKind 'aar' -ExpectedFilesByAbi $expectedReleaseNativeFiles -ZipalignPath $zipalignPath
    foreach ($probePath in $ProbeApk) {
        Assert-UgkZipNativePackage -PackagePath $probePath -PackageKind 'apk' -ExpectedFilesByAbi $expectedReleaseNativeFiles -ZipalignPath $zipalignPath
    }
} else {
    Write-Output '[INFO] Package checks skipped; pass -CheckPackages for Release AAR/APK and zipalign validation.'
}

Write-Output 'Terminal Runtime payload verification passed.'
