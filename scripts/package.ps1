param([string]$Version = '0.1.0', [switch]$Refresh)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseRoot = Join-Path (Split-Path -Parent $projectRoot) 'releases'
$bundle = Join-Path $releaseRoot "AgentLens-$Version"
if ((Test-Path -LiteralPath $bundle) -and -not $Refresh) { throw "Release directory already exists: $bundle. Use a new version, or -Refresh for this generated release." }
New-Item -ItemType Directory -Force -Path $bundle | Out-Null
$include = @('README.md','.gitignore','.dockerignore','start-demo.cmd','backend/pom.xml','frontend/package.json','frontend/package-lock.json','frontend/.npmrc','frontend/tsconfig.json','frontend/vite.config.ts','frontend/index.html','frontend/playwright.config.ts')
foreach ($file in $include) {
  $target = Join-Path $bundle $file
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
  Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $target
}
foreach ($dir in @('backend/src','frontend/src','frontend/public','frontend/tests','contracts','demo-data','docs','scripts')) {
  $target = Join-Path $bundle $dir
  New-Item -ItemType Directory -Force -Path $target | Out-Null
  Get-ChildItem -LiteralPath (Join-Path $projectRoot $dir) -Force | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'deployment'),(Join-Path $bundle 'backend/target') | Out-Null
foreach ($name in @('Dockerfile','compose.yml','nginx.conf','.env.example','maven-settings.xml')) { Copy-Item -LiteralPath (Join-Path $projectRoot "deployment/$name") -Destination (Join-Path $bundle "deployment/$name") }
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend/target/agentlens.jar') -Destination (Join-Path $bundle 'backend/target/agentlens.jar')
$manifest = Get-ChildItem -LiteralPath $bundle -Recurse -File | Where-Object { $_.Name -ne 'SHA256-MANIFEST.json' } | ForEach-Object {
  [pscustomobject]@{path=$_.FullName.Substring($bundle.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower();bytes=$_.Length}
}
$manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $bundle 'SHA256-MANIFEST.json') -Encoding UTF8
$archive = Join-Path $releaseRoot "AgentLens-$Version.zip"
Compress-Archive -LiteralPath $bundle -DestinationPath $archive -Force
Get-Item -LiteralPath $archive | Select-Object FullName,Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256
