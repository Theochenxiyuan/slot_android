# Builds the Cart Studio fork into app/src/main/assets/studio.
# Normal Gradle builds use the committed assets and never run this script.
param(
    [string]$WasmPack = 'wasm-pack',
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$studio = $PSScriptRoot
$repo = Resolve-Path (Join-Path $studio '..\..')
$work = Join-Path $repo 'work\studio-build'
$slot = Join-Path $work 'slot'
$copy = Join-Path $work 'slot-cart-studio'
$assets = Join-Path $repo 'app\src\main\assets\studio'
$pin = (Get-Content (Join-Path $studio 'slot.ref') -Raw).Trim()

function Invoke-Checked([string]$File, [string[]]$Arguments, [string]$Directory) {
    Push-Location $Directory
    try {
        & $File @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$File $($Arguments -join ' ') failed with $LASTEXITCODE" }
    } finally { Pop-Location }
}

New-Item -ItemType Directory -Force $work | Out-Null
# The studio compiles slot's own cart face from ../slot at the commit slot.ref names.
if (-not (Test-Path (Join-Path $slot '.git'))) {
    Invoke-Checked git @('clone', '--quiet', 'https://github.com/BrandonKowalski/slot.git', $slot) $work
}
$head = (git -C $slot rev-parse HEAD).Trim()
if ($head -ne $pin) {
    git -C $slot cat-file -e "$pin^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) { Invoke-Checked git @('fetch', '--quiet', 'origin') $slot }
    Invoke-Checked git @('checkout', '--quiet', $pin) $slot
}

robocopy $studio $copy /MIR /XD target out pkg /XF android-build.ps1 /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }

Invoke-Checked $WasmPack @('build', '--release', '--target', 'web', '--no-typescript', '--out-dir', 'web/pkg') $copy
if ($Test) {
    # slot-ui, the native face reference, only builds on Linux and macOS.
    if ($env:OS -ne 'Windows_NT') { Invoke-Checked cargo @('test') $copy }
    $tests = Get-ChildItem (Join-Path $copy 'web') -Filter '*.test.mjs' | ForEach-Object { $_.FullName }
    Invoke-Checked node (@('--test') + $tests) $copy
}

robocopy (Join-Path $copy 'web') $assets /MIR /XF *.test.mjs .gitignore package.json README.md /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }
if ($Test) { Invoke-Checked node @((Join-Path $studio 'tools\verify-preview.mjs')) $repo }
Write-Output "Studio assets: $assets"
