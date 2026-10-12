param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend\target\agentlens.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the application first: powershell -File scripts\build.ps1' }
Set-Location $projectRoot
Write-Host "AgentLens: http://127.0.0.1:$Port"
Write-Host 'Demo account: admin / Demo2026! (unless DEMO_PASSWORD was changed)'
Write-Host 'Press Ctrl+C to stop. Data is persisted in .\data'
java -jar $jar "--server.port=$Port"
