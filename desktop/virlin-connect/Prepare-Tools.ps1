[CmdletBinding()]
param([string]$ScrcpyDirectory)

$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot 'src-tauri\resources\scrcpy'

if (-not $ScrcpyDirectory) {
    $wingetRoot = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    $candidate = @(Get-ChildItem -LiteralPath $wingetRoot -Directory -Filter 'Genymobile.scrcpy_*' -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Filter 'scrcpy.exe' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1)
    if ($candidate.Count -eq 0) {
        throw 'scrcpy was not found. Install the official Genymobile.scrcpy package with WinGet, or pass -ScrcpyDirectory.'
    }
    $ScrcpyDirectory = $candidate[0].Directory.FullName
}

$source = (Resolve-Path -LiteralPath $ScrcpyDirectory).Path
$required = @('scrcpy.exe', 'scrcpy-server', 'adb.exe', 'LICENSE.txt')
foreach ($name in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)) {
        throw "The selected scrcpy directory is missing $name."
    }
}

New-Item -ItemType Directory -Path $destination -Force | Out-Null
Get-ChildItem -LiteralPath $source -File | Where-Object {
    $_.Extension -eq '.dll' -or $_.Name -in @('scrcpy.exe', 'scrcpy-server', 'adb.exe', 'LICENSE.txt')
} | Copy-Item -Destination $destination -Force
Write-Host "Prepared official scrcpy tools from $source"
