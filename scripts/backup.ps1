param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $Destination) { $Destination = Join-Path $projectRoot ('backups\agentlens-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip') }
$dbFile = Join-Path $projectRoot 'data\agentlens.mv.db'
if (-not (Test-Path -LiteralPath $dbFile)) { throw 'Local H2 database not found' }
try { $lock = [System.IO.File]::Open($dbFile, 'Open', 'ReadWrite', 'None'); $lock.Dispose() } catch { throw 'Stop AgentLens before backing up the local database.' }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent ([System.IO.Path]::GetFullPath($Destination))) | Out-Null
Compress-Archive -LiteralPath (Join-Path $projectRoot 'data') -DestinationPath $Destination
Write-Host "Backup written: $Destination"
