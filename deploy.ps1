# русский текст для того чтобы редакторы не путали кодировку

# Native tools print their own diagnostics, PowerShell must not turn them into error records.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot

$env:JAVA_HOME = 'D:\Android\astudio\jbr'
$env:ANDROID_HOME = 'D:\Android\android-sdk'
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'D:\Android\gradle' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$appId = 'com.jm.xtravel'

function Show-Help {
    Write-Host @'
deploy.bat COMMAND [OPTIONS]

Where COMMANDs are:
  -?       - this help
  build    - build app and create apk (only if required)
  install  - build and install latest build on device

Where OPTIONs are:
  -device NAME    - set specific name for device. Required if more then one device connected to ADB
  -release        - create release version. If missing, debug version used
'@
}

function Fail([string]$Message, [string]$Hint) {
    Write-Host $Message -ForegroundColor Red
    if ($Hint) { Write-Host $Hint -ForegroundColor Yellow }
    exit 1
}

function Get-AppVersion {
    $file = Join-Path $root 'version.properties'
    if (-not (Test-Path $file)) { Fail "version.properties not found in $root" }
    $props = @{}
    Get-Content $file | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
    }
    return $props
}

# Builds the variant and returns path of the apk in dist. Gradle skips the work
# when nothing changed, in that case the existing package is reused.
function Invoke-Build([bool]$Release) {
    $version = Get-AppVersion
    $variant = if ($Release) { 'Release' } else { 'Debug' }

    Write-Host "Build: $variant, version $($version.versionName) ($($version.versionCode))" -ForegroundColor Cyan
    # Out-Host keeps gradle output off the pipeline, the function returns the apk path only.
    & (Join-Path $root 'gradlew.bat') "assemble$variant" | Out-Host
    if ($LASTEXITCODE -ne 0) { Fail 'Build failed, see gradle output above' }

    $apkDir = Join-Path $root "output\build\outputs\apk\$($variant.ToLower())"
    $apk = Get-ChildItem $apkDir -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) { Fail "APK not found in $apkDir" }

    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $suffix = if ($Release) { '' } else { '-debug' }
    $target = Join-Path $dist "xtravel-$($version.versionName).$($version.versionCode)$suffix.apk"

    if ((Test-Path $target) -and
        (Get-FileHash $target).Hash -eq (Get-FileHash $apk.FullName).Hash) {
        Write-Host "Package unchanged: $target" -ForegroundColor DarkGray
    } else {
        Copy-Item $apk.FullName $target -Force
        Write-Host "Package: $target" -ForegroundColor Green
    }
    return $target
}

function Get-Device([string]$Name) {
    $list = & $adb devices 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { Fail 'adb is not available' "Checked path: $adb" }

    $devices = @($list | Select-Object -Skip 1 |
        Where-Object { $_ -match '\sdevice$' } |
        ForEach-Object { ($_ -split '\s+')[0] })

    if (-not $devices) {
        Fail 'No device connected' 'Connect the phone, enable USB debugging and confirm the request on screen'
    }
    if ($Name) {
        if ($devices -notcontains $Name) { Fail "Device $Name not found" "Connected: $($devices -join ', ')" }
        return $Name
    }
    if ($devices.Count -gt 1) { Fail "Several devices connected: $($devices -join ', ')" 'Use -device NAME' }
    return $devices[0]
}

# Known install failures, message of adb is printed as is, hint explains what to do.
function Get-InstallHint([string]$Output) {
    switch -regex ($Output) {
        'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE.*signatures|signatures do not match' {
            return "Installed package is signed with another key. Remove it first: adb uninstall $appId (application data will be lost)"
        }
        'INSTALL_FAILED_USER_RESTRICTED' {
            return 'MIUI blocks the install. Enable "Install via USB" and "USB debugging (Security settings)" in developer options'
        }
        'INSTALL_FAILED_VERSION_DOWNGRADE' {
            return 'Installed package has a greater versionCode. Increase it in version.properties or remove the application'
        }
        'INSTALL_FAILED_INSUFFICIENT_STORAGE' {
            return 'Not enough free space on the device'
        }
        'INSTALL_FAILED_NO_MATCHING_ABIS' {
            return 'Debug package is built for another CPU architecture, see DEBUG_ABI in local.properties'
        }
        'device unauthorized' {
            return 'Confirm the debugging request on the phone screen'
        }
    }
    return $null
}

function Invoke-Install([string]$Apk, [string]$Name) {
    $serial = Get-Device $Name
    Write-Host "Install on $serial" -ForegroundColor Cyan

    $output = & $adb -s $serial install -r $Apk 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) {
        $reason = ($output | Where-Object { $_ -match 'Failure|error|Error' } | Select-Object -First 1)
        if (-not $reason) { $reason = ($output | Select-Object -Last 1) }
        Fail "Install failed: $reason" (Get-InstallHint ($output -join "`n"))
    }

    # am start, not monkey: monkey thaws the rotation on exit and so turns the auto-rotation of the phone back on.
    $started = & $adb -s $serial shell am start -n "$appId/.MainActivity" 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0 -or ($started | Where-Object { $_ -match 'Error' })) {
        Fail 'Application was installed but not started' ($started -join "`n")
    }
    Write-Host 'Installed and started.' -ForegroundColor Green
}

$command = $null
$device = $null
$release = $false

for ($i = 0; $i -lt $args.Count; $i++) {
    $arg = [string]$args[$i]
    # Every branch breaks: switch -regex runs all matching patterns, so an option would also hit the '^-' catch-all.
    switch -regex ($arg) {
        '^(-\?|/\?|-h|--help|help)$' { $command = 'help'; break }
        '^-release$'                 { $release = $true; break }
        '^-device$'                  { $i++; $device = [string]$args[$i]; break }
        '^-'                         { Write-Host "Unknown option: $arg" -ForegroundColor Red; Show-Help; exit 1 }
        default {
            if ($command) { Write-Host "Unexpected argument: $arg" -ForegroundColor Red; Show-Help; exit 1 }
            $command = $arg
        }
    }
}

if ($device -and $command -ne 'install') {
    Write-Host 'Option -device is used by the install command only' -ForegroundColor Yellow
}

switch ($command) {
    'help'    { Show-Help }
    'build'   { Invoke-Build $release | Out-Null }
    'install' { Invoke-Install (Invoke-Build $release) $device }
    $null     { Show-Help; exit 1 }
    default   { Write-Host "Unknown command: $command" -ForegroundColor Red; Show-Help; exit 1 }
}
