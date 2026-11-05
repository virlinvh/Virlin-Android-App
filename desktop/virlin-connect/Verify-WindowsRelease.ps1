param(
  [Parameter(Mandatory = $true)][string]$InstallerPath,
  [Parameter(Mandatory = $true)][string]$AppPath
)

$ErrorActionPreference = 'Stop'

foreach ($path in @($AppPath, $InstallerPath)) {
  $resolved = (Resolve-Path -LiteralPath $path).Path
  $signature = Get-AuthenticodeSignature -LiteralPath $resolved
  if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate) {
    throw "Release blocked: $resolved has no valid trusted Authenticode signature (status: $($signature.Status))."
  }
  Write-Host "Verified signed release file: $resolved"
  Write-Host "Publisher: $($signature.SignerCertificate.Subject)"
}
