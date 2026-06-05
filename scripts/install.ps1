# qkt - Windows one-line installer.
#
# Fetches the latest GitHub release zip, extracts under %LOCALAPPDATA%\Programs\qkt,
# adds it to the user PATH, and sets QKT_HOME so `qkt editor install` finds its files.
#
# Usage:
#   irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
#
#   # Pin a version:
#   $env:QKT_VERSION='v0.29.14'; irm .../install.ps1 | iex
#
#   # Install from a local zip instead of downloading (used by CI smoke):
#   $env:QKT_ARCHIVE='C:\path\qkt-0.29.14-windows-x64.zip'; irm .../install.ps1 | iex

$ErrorActionPreference = 'Stop'

$Repo    = if ($env:QKT_REPO)    { $env:QKT_REPO }    else { 'elitekaycy/qkt' }
$Version = if ($env:QKT_VERSION) { $env:QKT_VERSION } else { 'latest' }
$Prefix  = if ($env:QKT_PREFIX)  { $env:QKT_PREFIX }  else { Join-Path $env:LOCALAPPDATA 'Programs\qkt' }

function Say  ($m) { Write-Host "==> $m" -ForegroundColor Blue }
function Ok   ($m) { Write-Host "OK  $m" -ForegroundColor Green }
function Warn ($m) { Write-Warning $m }

# --- obtain the zip (download, or use a local archive for testing) ---
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("qkt-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
    if ($env:QKT_ARCHIVE) {
        $zip = $env:QKT_ARCHIVE
        Say "Using local archive $zip"
        if (-not (Test-Path $zip)) { throw "QKT_ARCHIVE not found: $zip" }
    } else {
        if ($Version -eq 'latest') {
            Say "Resolving latest qkt release from $Repo ..."
            $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest"
            $Version = $rel.tag_name
            if (-not $Version) { throw 'could not determine latest release tag' }
            Ok "Latest release: $Version"
        }
        $verNum = $Version.TrimStart('v')
        $asset  = "qkt-$verNum-windows-x64.zip"
        $url    = "https://github.com/$Repo/releases/download/$Version/$asset"
        $zip    = Join-Path $tmp $asset
        Say "Downloading $asset"
        Invoke-WebRequest -Uri $url -OutFile $zip
    }

    # --- extract (wipe prior install so versions don't accrete) ---
    Say "Extracting to $Prefix"
    if (Test-Path $Prefix) { Remove-Item -Recurse -Force $Prefix }
    New-Item -ItemType Directory -Path $Prefix -Force | Out-Null
    $unpack = Join-Path $tmp 'unpack'
    Expand-Archive -Path $zip -DestinationPath $unpack -Force
    # The zip has a top-level qkt/ directory; move its contents into $Prefix.
    Copy-Item -Path (Join-Path $unpack 'qkt\*') -Destination $Prefix -Recurse -Force

    $exe = Join-Path $Prefix 'qkt.exe'
    if (-not (Test-Path $exe)) { throw "extracted archive did not contain qkt.exe under $Prefix" }
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

# --- QKT_HOME (user env) so `qkt editor install` finds share/editor ---
[Environment]::SetEnvironmentVariable('QKT_HOME', $Prefix, 'User')
$env:QKT_HOME = $Prefix

# --- add to user PATH idempotently ---
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -notlike "*$Prefix*") {
    $newPath = if ([string]::IsNullOrEmpty($userPath)) { $Prefix } else { "$userPath;$Prefix" }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Ok "Added $Prefix to your user PATH"
} else {
    Ok "$Prefix already on your user PATH"
}
$env:Path = "$env:Path;$Prefix"

# --- verify ---
$ver = & $exe --version 2>&1 | Select-Object -First 1
Ok "Installed: $ver"
Write-Host ''
Ok 'qkt installed. Open a NEW terminal, then try:'
Write-Host '    qkt --version'
Write-Host '    qkt --help'
Write-Host ''
Write-Host 'Docs: https://elitekaycy.github.io/qkt/'
