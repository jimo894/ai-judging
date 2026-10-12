param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
  Push-Location (Join-Path $projectRoot 'frontend')
  try {
    if (-not (Test-Path -LiteralPath 'node_modules')) { npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
  } finally { Pop-Location }
  $staticPath = Join-Path $projectRoot 'backend\src\main\resources\static'
  New-Item -ItemType Directory -Force -Path $staticPath | Out-Null
  Copy-Item -Path (Join-Path $projectRoot 'frontend\dist\*') -Destination $staticPath -Recurse -Force
  $bundledMaven = Join-Path $projectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
  $maven = if (Test-Path -LiteralPath $bundledMaven) { $bundledMaven } else { 'mvn.cmd' }
  $buildArgs = @('-s', 'deployment/maven-settings.xml', '-f', 'backend/pom.xml', "-Dmaven.repo.local=$projectRoot\.tools\m2", 'package')
  if ($SkipTests) { $buildArgs += '-DskipTests' }
  & $maven @buildArgs
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
  Write-Host 'Build complete: backend\target\agentlens.jar'
} finally { Pop-Location }
