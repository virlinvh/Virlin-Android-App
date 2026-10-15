[CmdletBinding()]
param(
    [ValidateSet('Usb', 'Wifi')]
    [string]$Mode = 'Usb',
    [string]$Serial,
    [switch]$List,
    [switch]$AllowEmulator
)

$ErrorActionPreference = 'Stop'

function Find-Executable([string]$name, [string[]]$candidates) {
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$adb = Find-Executable 'adb.exe' @(
    (Join-Path $sdkRoot 'platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
)
if (-not $adb) { throw 'Android platform-tools (adb) are not installed.' }

$scrcpyCandidates = @()
$wingetRoot = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
if (Test-Path -LiteralPath $wingetRoot) {
    $scrcpyCandidates = @(Get-ChildItem -LiteralPath $wingetRoot -Directory -Filter 'Genymobile.scrcpy_*' |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Filter 'scrcpy.exe' } |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object FullName)
}
$scrcpy = Find-Executable 'scrcpy.exe' $scrcpyCandidates
if (-not $scrcpy) {
    throw 'scrcpy is not installed. Install the official Genymobile.scrcpy package with WinGet.'
}

$deviceLines = @(& $adb devices -l)
if ($LASTEXITCODE -ne 0) { throw 'adb could not list connected devices.' }
$devices = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\b') {
        [pscustomobject]@{
            Serial = $Matches[1]
            Kind = if ($Matches[1] -match '^emulator-' -or $_ -match 'model:sdk_|product:sdk_') {
                'Emulator'
            } elseif ($Matches[1] -match ':\d+$') { 'Wifi' } else { 'Usb' }
        }
    }
} | Where-Object { $null -ne $_ })

if ($List) {
    if ($devices.Count -eq 0) { Write-Host 'No authorized online devices.' }
    else { $devices | Format-Table -AutoSize }
    return
}

$matches = @($devices | Where-Object {
    if ($Serial) { $_.Serial -eq $Serial }
    else { $_.Kind -eq $Mode }
})
if ($matches.Count -ne 1) {
    throw "Expected one $Mode device; found $($matches.Count). Connect and authorize the phone, or pass -Serial. Use -List to inspect devices."
}
$device = $matches[0]
if ($device.Kind -eq 'Emulator' -and -not $AllowEmulator) {
    throw 'Refusing to select an emulator. Use -AllowEmulator only for an explicit test.'
}
$target = $device.Serial

$installed = @(& $adb -s $target shell pm path com.virlin.app)
if ($LASTEXITCODE -ne 0 -or -not ($installed | Where-Object { $_ -match '^package:' })) {
    throw "Virlin is not installed on $target. Install the app on your phone first; this launcher never installs or overwrites it."
}

Write-Host "Opening Virlin on $target ($($device.Kind))..."
& $adb -s $target shell monkey -p com.virlin.app -c android.intent.category.LAUNCHER 1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not open Virlin on the selected device.' }

# Use the same platform-tools installation that selected the device above.
$env:ADB = $adb
Write-Host 'Starting scrcpy. Closing its window ends this desktop session; phone data stays on the phone.'
& $scrcpy "--serial=$target"
if ($LASTEXITCODE -ne 0) { throw "scrcpy exited with code $LASTEXITCODE." }
