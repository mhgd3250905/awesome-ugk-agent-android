[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$X86Package,
    [Parameter(Mandatory)]
    [string]$Arm64Package,
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$WorkDirectory,
    [Parameter(Mandatory)]
    [string]$PatchelfTool,
    [string]$NdkRoot = $env:ANDROID_NDK_ROOT,
    [string]$PatchelfDockerImage = 'node@sha256:7af03b14a13c8cdd38e45058fd957bf00a72bbe17feac43b1c15a689c029c732',
    [switch]$ReplaceExisting
)

$ErrorActionPreference = 'Stop'

$pythonVersion = '3.14'
$pythonDistributionVersion = '3.14.6'
$expectedPackages = @{
    x86_64 = 'e04eb26607627e68d148f89de793372f54a345c91b13628567f24abcdd3bfa3e'
    aarch64 = '38bbe77d3167b5cd554e03b1021324926f09f3825202b065951dd7638e9c37e5'
}
$nativeLibraryNames = @(
    "libpython$pythonVersion.so",
    'libcrypto_python.so',
    'libssl_python.so',
    'libsqlite3_python.so'
)
$nativeExtensionPrefix = 'libugk_pyext_'
$excludedStdlibRoots = @('test', 'idlelib', 'tkinter', 'ensurepip', 'pydoc_data')

if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    if ([string]::IsNullOrWhiteSpace($env:UGK_TERMINAL_VENDOR_DIR)) {
        throw 'Pass -WorkDirectory on an external volume, or set UGK_TERMINAL_VENDOR_DIR.'
    }
    $WorkDirectory = Join-Path $env:UGK_TERMINAL_VENDOR_DIR "build/python-$pythonDistributionVersion-runtime"
}
if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    throw 'Pass -NdkRoot or set ANDROID_NDK_ROOT so the imported ELF files can be verified.'
}

function Assert-FileHash {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ExpectedSha256
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Package is missing: $Path"
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        throw "SHA-256 mismatch for ${Path}: expected $ExpectedSha256, got $actual"
    }
}

function Copy-File {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )

    $parent = Split-Path -Parent $Destination
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Get-RelativePath {
    param(
        [Parameter(Mandatory)][string]$Base,
        [Parameter(Mandatory)][string]$Path
    )

    return $Path.Substring($Base.Length + 1).Replace('\', '/')
}

function Include-StdlibFile {
    param([Parameter(Mandatory)][string]$RelativePath)

    if ($RelativePath.StartsWith('lib-dynload/')) {
        return $false
    }
    $topLevel = $RelativePath.Split('/')[0]
    return $topLevel -notin $excludedStdlibRoots
}

function Exclude-NativeExtension {
    param([Parameter(Mandatory)][string]$Name)

    # CPython's own test extensions are not part of the runtime profile.
    return $Name -match '^(_test|_xxtest|xx)'
}

function Expand-Package {
    param(
        [Parameter(Mandatory)][string]$Archive,
        [Parameter(Mandatory)][string]$Architecture
    )

    $destination = Join-Path $WorkDirectory "package-$Architecture-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    & tar.exe -xzf $Archive -C $destination
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to extract Python package: $Archive"
    }
    $prefix = Join-Path $destination 'prefix'
    if (-not (Test-Path -LiteralPath $prefix -PathType Container)) {
        throw "Python package did not contain prefix/: $Archive"
    }
    return $prefix
}

function Write-StdlibArchive {
    param(
        [Parameter(Mandatory)][string]$SourceDirectory,
        [Parameter(Mandatory)][string]$Destination
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $Destination,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        Get-ChildItem -LiteralPath $SourceDirectory -Recurse -File |
            Where-Object { $_.Name -ne 'manifest.sha256' } |
            Sort-Object FullName |
            ForEach-Object {
                $relative = Get-RelativePath -Base $SourceDirectory -Path $_.FullName
                $entry = $archive.CreateEntry(
                    $relative,
                    [System.IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = [DateTimeOffset]::new(
                    1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero
                )
                $input = [System.IO.File]::OpenRead($_.FullName)
                $output = $entry.Open()
                try {
                    $input.CopyTo($output)
                } finally {
                    $output.Dispose()
                    $input.Dispose()
                }
            }
    } finally {
        $archive.Dispose()
    }
}

function Move-FileToBackup {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$BackupDirectory
    )

    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Cannot back up missing file: $Source"
    }
    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $destination = Join-Path $BackupDirectory (Split-Path -Leaf $Source)
    if (Test-Path -LiteralPath $destination) {
        throw "Refusing to overwrite Python payload backup: $destination"
    }
    Move-Item -LiteralPath $Source -Destination $destination
}

Assert-FileHash -Path $X86Package -ExpectedSha256 $expectedPackages.x86_64
Assert-FileHash -Path $Arm64Package -ExpectedSha256 $expectedPackages.aarch64

$RepositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$moduleRoot = Join-Path $RepositoryRoot 'ugk-terminal-runtime-android'
if (-not (Test-Path -LiteralPath $moduleRoot -PathType Container)) {
    throw "Terminal Runtime module was not found: $moduleRoot"
}

New-Item -ItemType Directory -Force -Path $WorkDirectory | Out-Null
$x86Prefix = Expand-Package -Archive $X86Package -Architecture 'x86_64'
$armPrefix = Expand-Package -Archive $Arm64Package -Architecture 'aarch64'

$architectures = @(
    [pscustomobject]@{ Abi = 'x86_64'; Prefix = $x86Prefix },
    [pscustomobject]@{ Abi = 'arm64-v8a'; Prefix = $armPrefix }
)

$nativeStagingRoot = Join-Path $WorkDirectory "native-$([guid]::NewGuid().ToString('N'))"
$nativeFileCounts = @{}

foreach ($architecture in $architectures) {
    $libraryDirectory = Join-Path $architecture.Prefix 'lib'
    $nativeTarget = Join-Path $nativeStagingRoot $architecture.Abi

    foreach ($libraryName in $nativeLibraryNames) {
        $source = Join-Path $libraryDirectory $libraryName
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Required Python native library is missing: $source"
        }
        Copy-File -Source $source -Destination (Join-Path $nativeTarget $libraryName)
    }

    $extensionDirectory = Join-Path $libraryDirectory "python$pythonVersion/lib-dynload"
    $extensions = Get-ChildItem -LiteralPath $extensionDirectory -Filter '*.so' -File |
        Where-Object { -not (Exclude-NativeExtension $_.Name) } |
        Sort-Object Name
    if ($extensions.Count -eq 0) {
        throw "No production Python extension modules were found: $extensionDirectory"
    }
    foreach ($extension in $extensions) {
        # Android only extracts native files beginning with `lib` into
        # nativeLibraryDir. sitecustomize.py maps this physical filename back
        # to CPython's logical extension-module name at import time.
        Copy-File -Source $extension.FullName -Destination (
            Join-Path $nativeTarget "$nativeExtensionPrefix$($extension.Name)"
        )
    }

    $nativeFileCounts[$architecture.Abi] = $nativeLibraryNames.Count + $extensions.Count
    Write-Output "Staged Python native payload for $($architecture.Abi): $($nativeFileCounts[$architecture.Abi]) files"
}

$normalizeScript = Join-Path $PSScriptRoot 'normalize-python-sqlite-rpath.ps1'
& $normalizeScript `
    -PayloadRoot $nativeStagingRoot `
    -PatchelfTool $PatchelfTool `
    -NdkRoot $NdkRoot `
    -DockerImage $PatchelfDockerImage
if ($LASTEXITCODE -ne 0) {
    throw "Python SQLite RPATH normalization failed with exit code $LASTEXITCODE"
}

$assetTarget = Join-Path $moduleRoot "src/main/assets/ugk-terminal-runtime/python/$pythonDistributionVersion"
$assetStaging = Join-Path $WorkDirectory "assets-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $assetStaging | Out-Null
$stdlibAssetStaging = Join-Path $assetStaging "lib/python$pythonVersion"
New-Item -ItemType Directory -Force -Path $stdlibAssetStaging | Out-Null

$x86Stdlib = Join-Path $x86Prefix "lib/python$pythonVersion"
$x86Files = Get-ChildItem -LiteralPath $x86Stdlib -Recurse -File | Sort-Object FullName
foreach ($file in $x86Files) {
    $relative = Get-RelativePath -Base $x86Stdlib -Path $file.FullName
    if (Include-StdlibFile $relative) {
        Copy-File -Source $file.FullName -Destination (Join-Path $stdlibAssetStaging $relative)
    }
}

# The two official packages are byte-identical except for ABI-specific
# sysconfig/config files and build-details.json. Keep x86_64 build-details and
# merge the missing aarch64 files so either installed ABI can query sysconfig.
$armStdlib = Join-Path $armPrefix "lib/python$pythonVersion"
$armFiles = Get-ChildItem -LiteralPath $armStdlib -Recurse -File | Sort-Object FullName
foreach ($file in $armFiles) {
    $relative = Get-RelativePath -Base $armStdlib -Path $file.FullName
    if (-not (Include-StdlibFile $relative)) {
        continue
    }
    $destination = Join-Path $stdlibAssetStaging $relative
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
        Copy-File -Source $file.FullName -Destination $destination
        continue
    }
    $sameContent = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash -eq
        (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
    if (-not $sameContent -and $relative -ne 'build-details.json') {
        throw "Unexpected ABI-specific Python standard-library file: $relative"
    }
}

$siteCustomizeSource = Join-Path $PSScriptRoot 'python-sitecustomize.py'
if (-not (Test-Path -LiteralPath $siteCustomizeSource -PathType Leaf)) {
    throw "UGK Python import bridge is missing: $siteCustomizeSource"
}
Copy-File -Source $siteCustomizeSource -Destination (Join-Path $stdlibAssetStaging 'sitecustomize.py')

$manifestPath = Join-Path $assetStaging 'manifest.sha256'
$manifestEntries = Get-ChildItem -LiteralPath $assetStaging -Recurse -File |
    Sort-Object FullName |
    ForEach-Object {
        $relative = Get-RelativePath -Base $assetStaging -Path $_.FullName
        $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$sha256  $relative"
    }
[System.IO.File]::WriteAllLines($manifestPath, $manifestEntries, [System.Text.UTF8Encoding]::new($false))

$assetArchiveStaging = Join-Path $WorkDirectory "asset-archive-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $assetArchiveStaging | Out-Null
Copy-File -Source $manifestPath -Destination (Join-Path $assetArchiveStaging 'manifest.sha256')
Write-StdlibArchive -SourceDirectory $assetStaging -Destination (Join-Path $assetArchiveStaging 'stdlib.zip')

$assetParent = Split-Path -Parent $assetTarget
New-Item -ItemType Directory -Force -Path $assetParent | Out-Null
$nativeBackupRoot = Join-Path $WorkDirectory "superseded-native-$([guid]::NewGuid().ToString('N'))"
foreach ($architecture in $architectures) {
    $nativeTarget = Join-Path $moduleRoot "src/main/jniLibs/$($architecture.Abi)"
    $existingExtensions = @(
        Get-ChildItem -LiteralPath $nativeTarget -File -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -like "$nativeExtensionPrefix*.so" -or
                $_.Name -match '\.cpython-314-.*-linux-android\.so$'
            }
    )
    if ($existingExtensions.Count -gt 0 -and -not $ReplaceExisting) {
        throw "Existing Python extension payload found at $nativeTarget. Re-run with -ReplaceExisting to back it up and replace it."
    }
    foreach ($existing in $existingExtensions) {
        Move-FileToBackup -Source $existing.FullName -BackupDirectory (Join-Path $nativeBackupRoot $architecture.Abi)
    }

    Get-ChildItem -LiteralPath (Join-Path $nativeStagingRoot $architecture.Abi) -File |
        ForEach-Object {
            Copy-File -Source $_.FullName -Destination (Join-Path $nativeTarget $_.Name)
        }
    Write-Output "Published Python native payload for $($architecture.Abi): $($nativeFileCounts[$architecture.Abi]) files"
}

$assetBackup = $null
if (Test-Path -LiteralPath $assetTarget) {
    if (-not $ReplaceExisting) {
        throw "Existing Python asset tree found at $assetTarget. Re-run with -ReplaceExisting to back it up and replace it."
    }
    $assetBackup = Join-Path $WorkDirectory "superseded-assets-$([guid]::NewGuid().ToString('N'))"
    Move-Item -LiteralPath $assetTarget -Destination $assetBackup
}
try {
    Move-Item -LiteralPath $assetArchiveStaging -Destination $assetTarget
} catch {
    if ($null -ne $assetBackup -and -not (Test-Path -LiteralPath $assetTarget)) {
        Move-Item -LiteralPath $assetBackup -Destination $assetTarget
    }
    throw
}

$assetFiles = Get-ChildItem -LiteralPath $assetTarget -Recurse -File
[pscustomobject]@{
    PythonVersion = $pythonDistributionVersion
    NativePayloadFilesPerAbi = $nativeFileCounts['x86_64']
    AssetFiles = $assetFiles.Count
    AssetBytes = ($assetFiles | Measure-Object Length -Sum).Sum
    ManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assetTarget 'manifest.sha256')).Hash.ToLowerInvariant()
} | Format-List
