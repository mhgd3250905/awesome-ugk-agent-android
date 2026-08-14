param(
    [string]$GroupId = 'com.ugk.pi',
    [string]$ArtifactId = 'ugk-pi-android',
    [string]$ArtifactVersion = '0.1.0',
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradleWrapper = Join-Path $repositoryRoot 'gradlew.bat'
$workRoot = Join-Path $repositoryRoot (Join-Path 'build\sdk-core-consumer' ([Guid]::NewGuid().ToString('N')))
$localMavenRepository = Join-Path $workRoot 'maven-repository'
$consumerRoot = Join-Path $workRoot 'consumer'
$publicationRoot = Join-Path $repositoryRoot 'ugk-pi-android\build\publications\release'
$aarPath = Join-Path $repositoryRoot 'ugk-pi-android\build\outputs\aar\ugk-pi-android-release.aar'

$expectedDependencies = [ordered]@{
    'org.jetbrains.kotlinx:kotlinx-coroutines-core' = '1.7.3'
    'org.jetbrains.kotlinx:kotlinx-serialization-json' = '1.4.0'
    'org.jetbrains.kotlin:kotlin-stdlib' = '2.2.21'
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $gradleWrapper @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code ${LASTEXITCODE}: $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Write-FixtureFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Content
    )

    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
}

function Get-XmlChildText {
    param(
        [Parameter(Mandatory)][System.Xml.XmlNode]$Node,
        [Parameter(Mandatory)][string]$Name
    )

    $child = $Node.SelectSingleNode("*[local-name()='$Name']")
    if ($null -eq $child) {
        throw "XML node is missing '$Name'."
    }
    return $child.InnerText
}

function Assert-Equal {
    param(
        [Parameter(Mandatory)][string]$Expected,
        [Parameter(Mandatory)][string]$Actual,
        [Parameter(Mandatory)][string]$Description
    )

    if ($Expected -ne $Actual) {
        throw "${Description}: expected '$Expected', got '$Actual'."
    }
}

function Assert-DependencySet {
    param(
        [Parameter(Mandatory)][object[]]$Dependencies,
        [Parameter(Mandatory)][string]$Description
    )

    $actual = [ordered]@{}
    foreach ($dependency in $Dependencies) {
        $coordinate = "$($dependency.GroupId):$($dependency.ArtifactId)"
        if ($actual.Contains($coordinate)) {
            throw "$Description contains duplicate dependency '$coordinate'."
        }
        $actual[$coordinate] = $dependency.Version
    }

    $unexpected = @($actual.Keys | Where-Object { -not $expectedDependencies.Contains($_) })
    if ($unexpected.Count -gt 0) {
        throw "$Description contains unexpected dependencies: $($unexpected -join ', ')"
    }

    $missing = @($expectedDependencies.Keys | Where-Object { -not $actual.Contains($_) })
    if ($missing.Count -gt 0) {
        throw "$Description is missing dependencies: $($missing -join ', ')"
    }

    foreach ($coordinate in $expectedDependencies.Keys) {
        Assert-Equal -Expected $expectedDependencies[$coordinate] -Actual $actual[$coordinate] -Description "$Description dependency $coordinate version"
    }
}

try {
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper is missing: $gradleWrapper"
    }

    New-Item -ItemType Directory -Force -Path $localMavenRepository | Out-Null

    Invoke-Gradle -WorkingDirectory $repositoryRoot -Arguments @(
        ':ugk-pi-android:generatePomFileForReleasePublication',
        ':ugk-pi-android:bundleReleaseAar',
        ':ugk-pi-android:publishReleasePublicationToMavenLocal',
        "-Dmaven.repo.local=$localMavenRepository",
        '--console=plain'
    )

    $pomPath = Join-Path $publicationRoot 'pom-default.xml'
    $modulePath = Join-Path $publicationRoot 'module.json'
    foreach ($path in @($pomPath, $modulePath, $aarPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Core publication output is missing: $path"
        }
    }

    $publishedArtifactRoot = Join-Path $localMavenRepository (($GroupId.Replace('.', '\')) + "\$ArtifactId\$ArtifactVersion")
    $publishedFiles = @(
        (Join-Path $publishedArtifactRoot "$ArtifactId-$ArtifactVersion.aar"),
        (Join-Path $publishedArtifactRoot "$ArtifactId-$ArtifactVersion.pom"),
        (Join-Path $publishedArtifactRoot "$ArtifactId-$ArtifactVersion.module")
    )
    foreach ($path in $publishedFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Published Maven file is missing: $path"
        }
    }

    [xml]$pom = Get-Content -LiteralPath $pomPath -Raw
    $project = $pom.SelectSingleNode("/*[local-name()='project']")
    Assert-Equal -Expected $GroupId -Actual (Get-XmlChildText -Node $project -Name 'groupId') -Description 'POM groupId'
    Assert-Equal -Expected $ArtifactId -Actual (Get-XmlChildText -Node $project -Name 'artifactId') -Description 'POM artifactId'
    Assert-Equal -Expected $ArtifactVersion -Actual (Get-XmlChildText -Node $project -Name 'version') -Description 'POM version'

    $pomDependencies = @($project.SelectNodes("*[local-name()='dependencies']/*[local-name()='dependency']") | ForEach-Object {
        [pscustomobject]@{
            GroupId = Get-XmlChildText -Node $_ -Name 'groupId'
            ArtifactId = Get-XmlChildText -Node $_ -Name 'artifactId'
            Version = Get-XmlChildText -Node $_ -Name 'version'
        }
    })
    Assert-DependencySet -Dependencies $pomDependencies -Description 'POM'

    $module = Get-Content -LiteralPath $modulePath -Raw | ConvertFrom-Json
    Assert-Equal -Expected $GroupId -Actual ([string]$module.component.group) -Description 'Gradle module group'
    Assert-Equal -Expected $ArtifactId -Actual ([string]$module.component.module) -Description 'Gradle module artifact'
    Assert-Equal -Expected $ArtifactVersion -Actual ([string]$module.component.version) -Description 'Gradle module version'
    foreach ($variant in @($module.variants)) {
        $moduleDependencies = @($variant.dependencies | ForEach-Object {
            [pscustomobject]@{
                GroupId = [string]$_.group
                ArtifactId = [string]$_.module
                Version = [string]$_.version.requires
            }
        })
        Assert-DependencySet -Dependencies $moduleDependencies -Description "Gradle module variant '$($variant.name)'"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $aar = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $aarPath).Path)
    try {
        $aarEntries = @($aar.Entries | ForEach-Object { $_.FullName })
        foreach ($requiredEntry in @('AndroidManifest.xml', 'classes.jar')) {
            if ($requiredEntry -notin $aarEntries) {
                throw "Core AAR is missing required entry '$requiredEntry'."
            }
        }
        $nativeEntries = @($aarEntries | Where-Object { $_ -like '*.so' })
        if ($nativeEntries.Count -gt 0) {
            throw "Core AAR unexpectedly contains native entries: $($nativeEntries -join ', ')"
        }
    } finally {
        $aar.Dispose()
    }

    Write-FixtureFile -Path (Join-Path $consumerRoot 'settings.gradle.kts') -Content @'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(System.getProperty("ugk.core.repo") ?: error("ugk.core.repo is required")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "ugk-core-consumer-smoke"
include(":consumer")
'@
    Write-FixtureFile -Path (Join-Path $consumerRoot 'build.gradle.kts') -Content @'
plugins {
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
'@
    Write-FixtureFile -Path (Join-Path $consumerRoot 'gradle.properties') -Content @'
org.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8
android.useAndroidX=true
'@
    Write-FixtureFile -Path (Join-Path $consumerRoot 'consumer\build.gradle.kts') -Content @"
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ugkcoreconsumersmoke"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("${GroupId}:${ArtifactId}:${ArtifactVersion}")
}
"@
    Write-FixtureFile -Path (Join-Path $consumerRoot 'consumer\src\main\AndroidManifest.xml') -Content @'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
'@
    Write-FixtureFile -Path (Join-Path $consumerRoot 'consumer\src\main\java\com\example\ugkcoreconsumersmoke\CoreConsumerSmoke.kt') -Content @'
package com.example.ugkcoreconsumersmoke

import com.ugk.pi.android.AgentRuntime
import com.ugk.pi.android.AgentSession
import com.ugk.pi.android.LLMProvider
import com.ugk.pi.android.ModelRequest
import com.ugk.pi.android.ModelResponse

class CoreConsumerSmoke {
    fun createSession(): AgentSession = AgentSession("external-consumer")

    fun createRuntime(): AgentRuntime = AgentRuntime.Builder()
        .llmProvider(object : LLMProvider {
            override suspend fun generate(request: ModelRequest): ModelResponse {
                return ModelResponse(content = "consumer smoke")
            }
        })
        .build()
}
'@

    Invoke-Gradle -WorkingDirectory $consumerRoot -Arguments @(
        '--project-dir',
        $consumerRoot,
        ':consumer:assembleDebug',
        "-Dugk.core.repo=$localMavenRepository",
        '--console=plain'
    )

    $consumerArtifact = Join-Path $consumerRoot 'consumer\build\outputs\aar\consumer-debug.aar'
    if (-not (Test-Path -LiteralPath $consumerArtifact -PathType Leaf)) {
        throw "External consumer AAR is missing after smoke build: $consumerArtifact"
    }

    Write-Output "Core consumer boundary verification passed."
    Write-Output "Coordinates: $GroupId`:$ArtifactId`:$ArtifactVersion"
    Write-Output "POM dependencies: $($expectedDependencies.Keys -join ', ')"
    Write-Output "Consumer build: :consumer:assembleDebug"
    if ($KeepWorkDir) {
        Write-Output "Kept verification work directory: $workRoot"
    }
} finally {
    if (-not $KeepWorkDir -and (Test-Path -LiteralPath $workRoot)) {
        Remove-Item -LiteralPath $workRoot -Recurse -Force
    }
}
