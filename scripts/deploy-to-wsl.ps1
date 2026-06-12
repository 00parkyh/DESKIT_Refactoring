param(
    [string]$DistroName = "Ubuntu",
    [string]$BackendTargetDir = "/srv/livecommerce",
    [string]$FrontendTargetDir = "/var/www/deskit",
    [string]$ImageName = "deskit",
    [string]$ImageTag = "latest",
    [switch]$SkipBackendBuild,
    [switch]$SkipFrontendBuild,
    [switch]$SkipImageBuild
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $projectRoot "front"
$jarPath = Join-Path $projectRoot "build\libs\deskit-0.0.1-SNAPSHOT.jar"
$imageRef = "{0}:{1}" -f $ImageName, $ImageTag
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Path

function Invoke-Step {
    param(
        [string]$Message,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Message"
    & $Action
}

function Assert-PathExists {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Description not found: $Path"
    }
}

function Invoke-WslSh {
    param(
        [string]$Command
    )

    & wsl.exe -d $DistroName -u root -- sh -lc $Command
    if ($LASTEXITCODE -ne 0) {
        throw "WSL command failed: $Command"
    }
}

Assert-PathExists -Path $projectRoot -Description "Project root"
Assert-PathExists -Path $frontendRoot -Description "Frontend root"

if (-not $SkipBackendBuild) {
    Invoke-Step -Message "Build backend JAR" -Action {
        Push-Location $projectRoot
        try {
            & .\gradlew.bat bootJar
            if ($LASTEXITCODE -ne 0) {
                throw "Backend build failed."
            }
        }
        finally {
            Pop-Location
        }
    }
}

Assert-PathExists -Path $jarPath -Description "Backend JAR"

if (-not $SkipFrontendBuild) {
    Invoke-Step -Message "Build frontend assets" -Action {
        Push-Location $frontendRoot
        try {
            & $npmCommand run build
            if ($LASTEXITCODE -ne 0) {
                throw "Frontend build failed."
            }
        }
        finally {
            Pop-Location
        }
    }
}

$frontendDistPath = Join-Path $frontendRoot "dist"
Assert-PathExists -Path $frontendDistPath -Description "Frontend dist directory"

$wslJarPath = "/mnt/c/personal_project/refactoring/deskit/build/libs/deskit-0.0.1-SNAPSHOT.jar"
$wslFrontendDistPath = "/mnt/c/personal_project/refactoring/deskit/front/dist"

Invoke-Step -Message "Prepare WSL target directories" -Action {
    Invoke-WslSh "mkdir -p '$BackendTargetDir' '$FrontendTargetDir'"
}

Invoke-Step -Message "Copy backend JAR to WSL" -Action {
    Invoke-WslSh "cp '$wslJarPath' '$BackendTargetDir/'"
}

if (-not $SkipImageBuild) {
    Invoke-Step -Message "Build backend image $imageRef in WSL" -Action {
        Invoke-WslSh "cd '$BackendTargetDir' && docker build -t '$imageRef' ."
    }
}

Invoke-Step -Message "Copy frontend build files to WSL" -Action {
    Invoke-WslSh "cp -r '$wslFrontendDistPath/.' '$FrontendTargetDir/'"
}

Write-Host ""
Write-Host "Deployment automation completed."
Write-Host "Backend JAR : $jarPath -> $BackendTargetDir"
Write-Host "Frontend   : $frontendDistPath -> $FrontendTargetDir"
Write-Host "Image      : $imageRef"
