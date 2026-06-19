<#
    reclaim-wsl-space.ps1

    Reclaims host disk space consumed by WSL2 / Docker Desktop virtual disks (.vhdx).
    A .vhdx grows as data is written into it but never shrinks on its own, so deleting
    files *inside* the distro leaves the host file just as large. This script compacts
    the disks to give that dead space back to Windows.

    SAFE: it never deletes a vhdx. It only (optionally) prunes unused Docker data, then
    shuts WSL down and compacts the disks read-only.

    ---------------------------------------------------------------------------------
    HOW TO RUN
    ---------------------------------------------------------------------------------
    1. Close anything you're doing in WSL/Docker (this script shuts both down).
    2. Open Windows PowerShell as the SAME Windows user who owns the disks
       (Start menu -> type "PowerShell" -> Enter). It does NOT need to be elevated;
       the script raises its own UAC prompt for the compaction step.
    3. Run this one line:

           powershell -ExecutionPolicy Bypass -File C:\Projects\reclaim-wsl-space.ps1

    4. Click "Yes" on the UAC prompt, then answer the y/N questions it asks.
       Type 'y' + Enter to proceed; anything else aborts safely.

    Optional: skip the Docker prune step with
           powershell -ExecutionPolicy Bypass -File C:\Projects\reclaim-wsl-space.ps1 -NoDockerPrune

    Notes:
      - Run it as the user whose AppData holds the disks; paths resolve via
        $env:LOCALAPPDATA, so the username never has to be typed.
      - Nothing is modified until you confirm. The only step that deletes data is the
        optional "docker system prune", and only UNUSED images/volumes.
      - After it finishes, restart Docker Desktop manually when you next need it.
    ---------------------------------------------------------------------------------
#>

[CmdletBinding()]
param(
    # Skip the interactive "docker system prune" step.
    [switch]$NoDockerPrune
)

# --- Self-elevate to Administrator (diskpart compact requires it) -----------------
$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "Elevating to Administrator..." -ForegroundColor Yellow
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"")
    if ($NoDockerPrune) { $argList += '-NoDockerPrune' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
    return
}

$ErrorActionPreference = 'Stop'

function Format-GB([long]$bytes) { '{0,8:N2} GB' -f ($bytes / 1GB) }

# --- Locate the target virtual disks ----------------------------------------------
# Paths resolve under the *current Windows user's* AppData, so the Hebrew username in
# the path never has to be typed by hand.
$targets = @(
    [pscustomobject]@{ Name = 'Docker data'; Path = Join-Path $env:LOCALAPPDATA 'Docker\wsl\disk\docker_data.vhdx' }
    [pscustomobject]@{ Name = 'Ubuntu-22.04'; Path = Join-Path $env:LOCALAPPDATA 'Packages\CanonicalGroupLimited.Ubuntu22.04LTS_79rhkp1fndgsc\LocalState\ext4.vhdx' }
)

# Pick up any other large vhdx under LocalAppData (e.g. WSL swap disks) so they get
# released too, without hardcoding their per-boot temp paths.
Get-ChildItem -Path $env:LOCALAPPDATA -Recurse -Filter *.vhdx -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notin $targets.Path } |
    ForEach-Object {
        $targets += [pscustomobject]@{ Name = $_.Name; Path = $_.FullName }
    }

$targets = $targets | Where-Object { Test-Path $_.Path }

if (-not $targets) {
    Write-Host "No .vhdx files found under $env:LOCALAPPDATA." -ForegroundColor Red
    return
}

Write-Host "`n=== Current virtual-disk sizes ===" -ForegroundColor Cyan
$before = @{}
foreach ($t in $targets) {
    $len = (Get-Item $t.Path).Length
    $before[$t.Path] = $len
    "{0}  {1}  {2}" -f (Format-GB $len), $t.Name.PadRight(14), $t.Path | Write-Host
}

if ((Read-Host "`nProceed? This stops Docker Desktop and shuts down ALL WSL distros (y/N)") -notmatch '^[Yy]') {
    Write-Host "Aborted." -ForegroundColor Yellow
    return
}

# --- Step 1: prune unused Docker data while the engine is still running ------------
if (-not $NoDockerPrune -and (Get-Command docker -ErrorAction SilentlyContinue)) {
    if ((Read-Host "Run 'docker system prune -a --volumes' first? Removes UNUSED images/volumes (y/N)") -match '^[Yy]') {
        Write-Host "Pruning Docker..." -ForegroundColor Cyan
        docker system prune -a --volumes -f
    }
}

# --- Step 2: stop Docker Desktop, then shut down WSL ------------------------------
Write-Host "`nStopping Docker Desktop..." -ForegroundColor Cyan
Get-Process 'Docker Desktop', 'com.docker.backend', 'com.docker.service', 'vpnkit' `
    -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

Write-Host "Shutting down WSL (this ends any running WSL sessions)..." -ForegroundColor Cyan
wsl.exe --shutdown
Start-Sleep -Seconds 5

# --- Step 3: compact each disk via diskpart (attach read-only, compact, detach) ---
# The profile path can contain non-ASCII (e.g. Hebrew) characters that a diskpart script
# file cannot carry. Feed diskpart an ASCII path instead: the 8.3 short name, or — if 8.3
# generation is disabled on the volume — a temporary ASCII hardlink to the same file.
function Compact-Vhdx([string]$path) {
    $ascii = (New-Object -ComObject Scripting.FileSystemObject).GetFile($path).ShortPath
    $link  = $null
    if ($ascii -match '[^\x00-\x7F]') {
        $link  = 'C:\Windows\Temp\compact-{0}.vhdx' -f [guid]::NewGuid()
        New-Item -ItemType HardLink -Path $link -Target $path | Out-Null
        $ascii = $link
    }
    $script = 'C:\Windows\Temp\compact-vhdx.txt'   # ASCII path; $env:TEMP may be non-ASCII
    @(
        "select vdisk file=`"$ascii`""
        "attach vdisk readonly"
        "compact vdisk"
        "detach vdisk"
        "exit"
    ) | Set-Content -Path $script -Encoding ASCII
    try { diskpart /s $script }
    finally {
        Remove-Item $script -ErrorAction SilentlyContinue
        if ($link) { Remove-Item $link -ErrorAction SilentlyContinue }
    }
}

foreach ($t in $targets) {
    Write-Host "`nCompacting $($t.Name)..." -ForegroundColor Cyan
    try { Compact-Vhdx $t.Path }
    catch { Write-Host "  Failed: $($_.Exception.Message)" -ForegroundColor Red }
}

# --- Step 4: report what was reclaimed --------------------------------------------
Write-Host "`n=== After compaction ===" -ForegroundColor Cyan
$totalSaved = 0
foreach ($t in $targets) {
    if (-not (Test-Path $t.Path)) { continue }
    $now   = (Get-Item $t.Path).Length
    $saved = $before[$t.Path] - $now
    $totalSaved += $saved
    "{0}  (-{1})  {2}" -f (Format-GB $now), (Format-GB $saved).Trim(), $t.Name | Write-Host
}
Write-Host ("`nReclaimed {0} total." -f (Format-GB $totalSaved).Trim()) -ForegroundColor Green

# --- Optional: make the Ubuntu disk auto-shrink going forward ---------------------
if ((Read-Host "`nEnable auto-shrink (sparse) on Ubuntu-22.04 going forward? (y/N)") -match '^[Yy]') {
    wsl.exe --manage Ubuntu-22.04 --set-sparse true
}

Write-Host "`nDone. Restart Docker Desktop manually when you need it." -ForegroundColor Green
