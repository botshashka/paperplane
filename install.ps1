# PaperPlane installer for Windows
# Usage: irm https://raw.githubusercontent.com/botshashka/paperplane/main/install.ps1 | iex
$ErrorActionPreference = "Stop"

$Repo = "botshashka/paperplane"
$InstallDir = "$env:USERPROFILE\.paperplane"
$BinDir = "$InstallDir\bin"

# ── Check Java ──────────────────────────────────────────────────────

try {
    $javaVersion = & java -version 2>&1 | Select-Object -First 1
    if ($javaVersion -match '"(\d+)') {
        $majorVersion = [int]$Matches[1]
        if ($majorVersion -lt 21) {
            Write-Host "Error: Java 21+ is required, but found Java $majorVersion." -ForegroundColor Red
            Write-Host ""
            Write-Host "Install Java 21 from: https://adoptium.net"
            exit 1
        }
    }
} catch {
    Write-Host "Error: Java is not installed." -ForegroundColor Red
    Write-Host ""
    Write-Host "PaperPlane requires Java 21 or later."
    Write-Host "Install it from: https://adoptium.net"
    exit 1
}

# ── Fetch latest version ────────────────────────────────────────────

Write-Host "Fetching latest version..."
$Release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest"
$Version = $Release.tag_name -replace '^v', ''

if (-not $Version) {
    Write-Host "Error: Could not determine latest version" -ForegroundColor Red
    exit 1
}

# ── Download and extract ────────────────────────────────────────────

$DownloadUrl = "https://github.com/$Repo/releases/download/v$Version/ppl-$Version.zip"
$TmpZip = [System.IO.Path]::GetTempFileName() + ".zip"

Write-Host "Downloading ppl v$Version..."
Invoke-WebRequest -Uri $DownloadUrl -OutFile $TmpZip

# Clean previous installation but preserve jbr cache
if (Test-Path "$InstallDir\bin") { Remove-Item -Recurse -Force "$InstallDir\bin" }
if (Test-Path "$InstallDir\lib") { Remove-Item -Recurse -Force "$InstallDir\lib" }

# Extract to temp, then move contents
$TmpExtract = [System.IO.Path]::GetTempFileName() + "_extract"
Expand-Archive -Path $TmpZip -DestinationPath $TmpExtract -Force

# The zip contains a top-level directory, move contents up
$InnerDir = Get-ChildItem -Path $TmpExtract | Select-Object -First 1
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -Path "$($InnerDir.FullName)\*" -Destination $InstallDir -Recurse -Force

# Cleanup temp files
Remove-Item -Force $TmpZip
Remove-Item -Recurse -Force $TmpExtract

# ── Update PATH ─────────────────────────────────────────────────────

$UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($UserPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$BinDir;$UserPath", "User")

    # Broadcast WM_SETTINGCHANGE so open terminals pick up the new PATH
    if (-not ("Win32.NativeMethods" -as [Type])) {
        Add-Type -Namespace Win32 -Name NativeMethods -MemberDefinition @"
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(
    IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
"@
    }
    $HWND_BROADCAST = [IntPtr]0xffff
    $WM_SETTINGCHANGE = 0x1a
    $result = [UIntPtr]::Zero
    [Win32.NativeMethods]::SendMessageTimeout(
        $HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero,
        "Environment", 2, 5000, [ref]$result
    ) | Out-Null

    $PathUpdated = $true
} else {
    $PathUpdated = $false
}

# ── Done ────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  paperplane was installed successfully to ~\.paperplane" -ForegroundColor Green
Write-Host ""
if ($PathUpdated) {
    Write-Host "  Added ~\.paperplane\bin to PATH"
    Write-Host ""
}
Write-Host "  To get started, run:"
Write-Host "    ppl --help"
Write-Host ""
if ($PathUpdated) {
    Write-Host "  You may need to restart your terminal for PATH changes to take effect."
    Write-Host ""
}
