param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE '.android\The-Last-Stand-2-Release.keystore'),
    [string]$SigningPasswordEnvironment = ''
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Preparation and signing only. Installation/publication are separate steps.
$package = 'io.github.frostie77.laststand2'
$alias = 'the-last-stand-2-release'
& git submodule update --init --recursive
if ($LASTEXITCODE -ne 0) { throw 'Could not synchronize the pinned frontend source.' }
$dirty = @(& git status --porcelain --untracked-files=all --ignore-submodules=none)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) { throw 'Commit or remove local source changes before preparing a release.' }
$sourceSha = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceSha -notmatch '^[a-f0-9]{40}$') { throw 'Release source commit unavailable.' }
$source = Get-Content -Raw -LiteralPath 'app\build.gradle'
$versionMatch = [regex]::Match($source, "versionName\s+'(\d+\.\d+\.\d+)'")
$codeMatch = [regex]::Match($source, 'versionCode\s+(\d+)')
if (-not $versionMatch.Success -or -not $codeMatch.Success) { throw 'Android release version unavailable.' }
$version = $versionMatch.Groups[1].Value
$versionCode = [int]$codeMatch.Groups[1].Value
$published = Get-Content -Raw -LiteralPath 'updates\android-stable.json' | ConvertFrom-Json
if ($published.schema_version -ne 1 -or $published.channel -ne 'stable' -or $published.package -ne $package) {
    throw 'Wrong stable release feed.'
}
if ($null -ne $published.latest -and
    ($versionCode -le $published.latest.version_code -or ('v' + $version) -eq $published.latest.release_tag)) {
    throw 'Set a new version name and increase the Android version code for each public release.'
}
$pinFile = $KeystorePath + '.certificate.sha256'
if (-not (Test-Path -LiteralPath $KeystorePath) -and
    ((Test-Path -LiteralPath $pinFile) -or $null -ne $published.latest)) {
    throw 'Permanent release key missing. Restore it; a replacement key will not be generated.'
}
if ($SigningPasswordEnvironment -and
    -not [Environment]::GetEnvironmentVariable($SigningPasswordEnvironment)) {
    throw 'The requested signing password environment variable is empty.'
}

& (Join-Path $PSScriptRoot 'build-test.ps1') -PrepareOnly
$submodule = @(& git submodule status third_party/FrostFrontend)
$pinnedFrontend = (Get-Content -Raw -LiteralPath 'FROSTFRONTEND_REVISION').Trim()
if ($LASTEXITCODE -ne 0 -or $submodule.Count -ne 1 -or
    $submodule[0] -notmatch ('^ ' + [regex]::Escape($pinnedFrontend) + '\s')) {
    throw 'FrostFrontend must match the pinned source revision.'
}
$runtime = Join-Path $PSScriptRoot 'app\src\main\assets\www\ruffle'
$runtimeHashes = Get-Content -Raw -LiteralPath 'ruffle-0.7.1.sha256.json' | ConvertFrom-Json
foreach ($entry in $runtimeHashes.PSObject.Properties) {
    $file = Join-Path $runtime $entry.Name
    if (-not (Test-Path -LiteralPath $file) -or
        (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value) {
        throw "Ruffle runtime mismatch: $($entry.Name). Remove the local ruffle folder contents and rerun preparation."
    }
}
$gradle = Join-Path $PSScriptRoot '.bootstrap\gradle-8.11.1\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = (Get-Command gradle.bat -ErrorAction Stop).Source }
& $gradle ':app:assembleRelease'
if ($LASTEXITCODE -ne 0) { throw 'Release compilation failed.' }
$unsigned = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Unsigned release APK missing.' }
$tools = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
$aapt = Join-Path $tools 'aapt.exe'
foreach ($tool in @($zipalign, $apksigner, $aapt)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Android build tools 35.0.0 required: $tool" }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-EntryHash($entry) {
    $stream = $entry.Open()
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $digest.Dispose(); $stream.Dispose() }
}
function Confirm-Payload($apk) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        if (@($names | Where-Object { $_ -match '(?i)\.swf$' }).Count -ne 0) { throw 'Game SWF found inside release APK.' }
        foreach ($name in @('index.html', 'combat-audio.js')) {
            $packed = $archive.GetEntry('assets/www/' + $name)
            $expected = (Get-FileHash -LiteralPath (Join-Path 'app/src/main/assets/www' $name) -Algorithm SHA256).Hash.ToLowerInvariant()
            if (-not $packed -or (Get-EntryHash $packed) -cne $expected) {
                throw "Packaged web asset mismatch: $name"
            }
        }
        foreach ($entry in $runtimeHashes.PSObject.Properties) {
            $packed = $archive.GetEntry('assets/www/ruffle/' + $entry.Name)
            if (-not $packed -or (Get-EntryHash $packed) -ne $entry.Value) { throw "Packaged Ruffle mismatch: $($entry.Name)" }
        }
        $resources = @(& $aapt dump resources $apk)
        if ($LASTEXITCODE -ne 0 -or -not ($resources -match '\bdrawable/launcher_art\b')) {
            throw 'Approved launcher artwork is not registered as an Android resource.'
        }
        $badging = @(& $aapt dump badging $apk)
        if ($LASTEXITCODE -ne 0) { throw 'APK metadata inspection failed.' }
        $identity = "^package: name='" + [regex]::Escape($package) + "' versionCode='" + $versionCode + "' versionName='" + [regex]::Escape($version) + "'"
        if (-not ($badging -match $identity) -or $badging -match '^application-debuggable' -or
            -not ($badging -match "^sdkVersion:'26'")) { throw 'Release package, version, API or debuggable state is wrong.' }
        $icon = [regex]::Match(($badging -join "`n"), "(?m)^application:.*icon='([^']+)'")
        if (-not $icon.Success -or -not ($names -contains $icon.Groups[1].Value)) { throw 'Selected launcher icon missing.' }
        if (-not ($badging -match "^application-label:'The Last Stand 2'$")) { throw 'Release app label is wrong.' }
    } finally { $archive.Dispose() }
}
Confirm-Payload $unsigned

if (-not (Test-Path -LiteralPath $KeystorePath)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $KeystorePath) -Force | Out-Null
    Write-Host 'Creating the permanent The Last Stand 2 release key. Keep its password and keystore safe.'
    $keyArgs = @('-genkeypair', '-keystore', $KeystorePath, '-storetype', 'JKS', '-alias', $alias,
        '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000', '-dname', 'CN=The Last Stand 2 Android Release')
    if ($SigningPasswordEnvironment) {
        $keyArgs += @('-storepass:env', $SigningPasswordEnvironment, '-keypass:env', $SigningPasswordEnvironment)
    }
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') @keyArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release key creation failed.' }
}
$releaseDir = Join-Path $PSScriptRoot 'release'
New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
$work = Join-Path $releaseDir ('.signing-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
$apkName = 'The-Last-Stand-2-v' + $version + '-Android.apk'
try {
    $aligned = Join-Path $work 'aligned.apk'
    $candidate = Join-Path $work $apkName
    & $zipalign -P 16 -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    $signArgs = @('sign', '--ks', $KeystorePath, '--ks-key-alias', $alias,
        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $candidate)
    if ($SigningPasswordEnvironment) {
        $signArgs += @('--ks-pass', ('env:' + $SigningPasswordEnvironment), '--key-pass', ('env:' + $SigningPasswordEnvironment))
    }
    $signArgs += $aligned
    Write-Host 'Sign using the permanent release keystore password when prompted.'
    & $apksigner @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $verification = @(& $apksigner verify --verbose --print-certs $candidate)
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $verifiedText = $verification -join "`n"
    if ($verifiedText -notmatch '(?m)^Number of signers: 1\s*$' -or
        $verifiedText -notmatch 'Verified using v2 scheme.*true' -or
        $verifiedText -notmatch 'Verified using v3 scheme.*true' -or
        $verifiedText -match 'CN=Android Debug') { throw 'Expected release signatures missing.' }
    $certificate = [regex]::Match($verifiedText, 'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})')
    if (-not $certificate.Success) { throw 'Release certificate fingerprint unavailable.' }
    $certHash = $certificate.Groups[1].Value.ToLowerInvariant()
    if ((Test-Path -LiteralPath $pinFile) -and
        (Get-Content -Raw -LiteralPath $pinFile).Trim() -cne $certHash) { throw 'Signing key differs from the established local release identity.' }
    if ($null -ne $published.latest -and $published.latest.certificate_sha256 -cne $certHash) {
        throw 'Signing key differs from the published release.'
    }
    & $zipalign -c -P 16 4 $candidate
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment verification failed.' }
    Confirm-Payload $candidate
    Remove-Item -LiteralPath $aligned
    $finalSha = (& git rev-parse HEAD).Trim()
    $finalDirty = @(& git status --porcelain --untracked-files=all --ignore-submodules=none)
    if ($LASTEXITCODE -ne 0 -or $finalSha -cne $sourceSha -or $finalDirty.Count -ne 0) {
        throw 'Source changed during release preparation; rebuild from a clean commit.'
    }
    $hash = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
    $feed = [ordered]@{
        schema_version = 1; channel = 'stable'; package = $package
        latest = [ordered]@{
            package = $package; version_name = $version; version_code = $versionCode
            release_tag = 'v' + $version; source_commit = $sourceSha
            certificate_sha256 = $certHash; min_sdk = 26; apk_asset = $apkName
            apk_size = (Get-Item -LiteralPath $candidate).Length; apk_sha256 = $hash
        }
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText((Join-Path $work 'android-stable.json'), ($feed | ConvertTo-Json -Depth 5) + "`n", $utf8)
    [System.IO.File]::WriteAllText((Join-Path $work ($apkName + '.sha256.txt')), "$hash  $apkName`n", $utf8)
    Copy-Item -LiteralPath 'RELEASE_DRAFT.md' -Destination (Join-Path $work 'release-notes.md')
    if (-not (Test-Path -LiteralPath $pinFile)) {
        [System.IO.File]::WriteAllText($pinFile, $certHash + "`n", $utf8)
    }
    $output = Join-Path $releaseDir ('The-Last-Stand-2-v' + $version + '-' +
        $sourceSha.Substring(0, 8) + '-' + $hash.Substring(0, 8))
    if (Test-Path -LiteralPath $output) {
        foreach ($name in @($apkName, 'android-stable.json', ($apkName + '.sha256.txt'), 'release-notes.md')) {
            $existing = Join-Path $output $name
            if (-not (Test-Path -LiteralPath $existing) -or
                (Get-FileHash -LiteralPath $existing).Hash -cne (Get-FileHash -LiteralPath (Join-Path $work $name)).Hash) {
                throw 'The candidate directory already exists with different contents.'
            }
        }
    } else {
        [System.IO.Directory]::Move($work, $output)
    }
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
Write-Host 'PASS: SIGNED THE LAST STAND 2 RELEASE CANDIDATE VERIFIED'
Write-Host "Folder: $output"
Write-Host "APK: $(Join-Path $output $apkName)"
Write-Host "SHA256: $hash"
Write-Host "Certificate: $certHash"
Write-Host "Private keystore: $KeystorePath"
Write-Host 'Back up the private keystore and keep its password. Reuse this key for all future releases.'
Write-Host 'Update feed staged in this folder. Publish it only after the matching GitHub release APK is live.'
