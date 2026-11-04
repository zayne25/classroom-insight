# ============================================================
#  Build a delivery package (zip) for classroom-insight
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File scripts\package.ps1
#    powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Version 1.0.1 -WithRuntime -WithDbDump
#
#  Options:
#    -Version     version tag used in the zip name (default 1.0.0)
#    -WithRuntime include runtime/ (portable maven, ~70MB) for offline build
#    -WithDbDump  include db/classroom_data.sql (schema + demo data snapshot)
# ============================================================
param(
    [string]$Version = "1.0.0",
    [switch]$WithRuntime,
    [switch]$WithDbDump,
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    [string]$OutName = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$name = "classroom-insight-v$Version"
$stage = Join-Path $root "dist\$name"
$zipName = if ($OutName) { $OutName } else { "$name.zip" }
$zip = Join-Path $root "dist\$zipName"

Write-Host "[1/5] clean staging dir" -ForegroundColor Cyan
if (Test-Path $stage) {
    Remove-Item -Recurse -Force $stage
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null

Write-Host "[2/5] copy sources (exclude .git / target / backups / logs)" -ForegroundColor Cyan
$excludeDirs = @("\.git\", "\target\", "\backups\", "\dist\", "\runtime\")
$excludeFiles = @("*.log", "*.zip")
$items = Get-ChildItem $root -Force | Where-Object {
    $_.Name -notin @(".git", "target", "backups", "dist", "runtime") -and ($_.PSIsContainer -or $_.Name -notlike "*.log")
}
foreach ($item in $items) {
    if ($item.PSIsContainer) {
        robocopy $item.FullName (Join-Path $stage $item.Name) /E /NFL /NDL /NJH /NJS /XF *.log /XD target backups .git dist | Out-Null
    } else {
        Copy-Item $item.FullName (Join-Path $stage $item.Name) -Force
    }
}

if ($WithRuntime) {
    Write-Host "[2/5] include portable maven (offline build)" -ForegroundColor Cyan
    robocopy (Join-Path $root "runtime") (Join-Path $stage "runtime") /E /NFL /NDL /NJH /NJS | Out-Null
}

if ($WithDbDump) {
    Write-Host "[2/5] dump database (schema + demo data)" -ForegroundColor Cyan
    $dump = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe"
    if (-not (Test-Path $dump)) { $dump = "mysqldump" }
    $outFile = Join-Path $stage "db\classroom_data.sql"
    New-Item -ItemType Directory -Path (Join-Path $stage "db") -Force | Out-Null

    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $env:MYSQL_PWD = $DbPassword          # 避免 -p 明文告警
    & $dump -u $DbUser --default-character-set=utf8mb4 --databases classroom --routines --triggers --single-transaction --result-file=$outFile 2>&1 | Out-Null
    $ErrorActionPreference = $prevPref

    if ((Test-Path $outFile) -and (Get-Item $outFile).Length -gt 0) {
        $mb = [math]::Round((Get-Item $outFile).Length / 1MB, 2)
        Write-Host "      dump ok: $mb MB" -ForegroundColor Green
    } else {
        Write-Host "      dump failed, skip" -ForegroundColor Yellow
    }
}

Write-Host "[3/5] clear read-only flags (Compress-Archive fails on read-only files)" -ForegroundColor Cyan
Get-ChildItem $stage -Recurse -File -Force | ForEach-Object { $_.IsReadOnly = $false }

Write-Host "[3/5] write package manifest" -ForegroundColor Cyan
$manifest = @()
$manifest += "classroom-insight $Version"
$manifest += "built: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$manifest += ""
$manifest += "runtime:  JDK 17 (required) + MySQL 8.0 (required)"
$manifest += "run:      1) edit src/main/resources/application.yml datasource password"
$manifest += "          2) mysql -uroot -p < db/schema.sql"
$manifest += "          3) run.bat   (or: mvn spring-boot:run)"
$manifest += "          4) http://127.0.0.1:8080  ->  admin / 123456"
$manifest += ""
$manifest += "documents: README.md / docs/*.md / db/er.md"
[System.IO.File]::WriteAllLines((Join-Path $stage "PACKAGE_INFO.txt"), $manifest, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "[4/5] compress" -ForegroundColor Cyan
Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal -Force

Write-Host "[5/5] done" -ForegroundColor Green
$size = [math]::Round((Get-Item $zip).Length / 1MB, 2)
$count = (Get-ChildItem $stage -Recurse -File | Measure-Object).Count
Write-Host "  file : $zip"
Write-Host "  size : $size MB"
Write-Host "  files: $count"
