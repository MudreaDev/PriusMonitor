[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$Repository = 'MudreaDev/PriusMonitor',
    [string]$Title = 'Prius Monitor',
    [string]$NotesFile,
    [string]$ApkSignerPath
)

# Publish an already verified APK. The private signing key never leaves the local computer.
$ErrorActionPreference = 'Stop'
$expectedCertificate = '5564cfd75f2d8b5ec8962d6c945ee88f34348cae1a770e1138964317a2473fac'
if ($Repository -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*$') {
    throw 'Repository must have the form owner/name.'
}
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedNotes = if ($NotesFile) { (Resolve-Path -LiteralPath $NotesFile).Path } else { $null }
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Invoke-GitHub {
    param([string[]]$Arguments)
    $result = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub command failed: $($Arguments[0])" }
    return $result
}

Push-Location -LiteralPath $repositoryRoot
try {
    $null = Get-Command gh -ErrorAction Stop
    $commitOutput = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the source commit.' }
    $commit = ($commitOutput -join '').Trim()
    $changes = & git status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changes) { throw 'Commit and push the source changes before publishing the APK.' }
    $remoteCommit = Invoke-GitHub -Arguments @('api', "repos/$Repository/commits/$commit", '--jq', '.sha')
    if (($remoteCommit -join '').Trim() -ne $commit) { throw 'The source commit must exist on GitHub.' }

    if (!$ApkSignerPath) {
        $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else {
            Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        }
        $buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
            Sort-Object { [version]$_.Name } -Descending
        $ApkSignerPath = $buildTools | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
            Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    }
    if (!$ApkSignerPath) { throw 'Android SDK apksigner was not found.' }
    $signature = & $ApkSignerPath verify --print-certs $resolvedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $certificate = [regex]::Match(($signature -join "`n"), 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)').Groups[1].Value
    if ($certificate.Replace(':', '').ToLowerInvariant() -ne $expectedCertificate) {
        throw 'APK signing certificate differs from the installed Prius Monitor app.'
    }

    $apkHash = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $tag = "apk-$($commit.Substring(0, 12))-$($apkHash.Substring(0, 8))"
    $publishDirectory = Join-Path $repositoryRoot "delivery\publish-$tag-$([guid]::NewGuid().ToString('N'))"
    $null = New-Item -ItemType Directory -Path $publishDirectory
    $canonicalApk = Join-Path $publishDirectory 'PriusMonitor.apk'
    $checksumFile = Join-Path $publishDirectory 'PriusMonitor.apk.sha256'
    Copy-Item -LiteralPath $resolvedApk -Destination $canonicalApk
    [IO.File]::WriteAllText($checksumFile, "$apkHash  PriusMonitor.apk`n", [Text.Encoding]::ASCII)

    $existing = & gh release view $tag --repo $Repository --json url,isDraft,assets 2>$null
    if ($LASTEXITCODE -eq 0) {
        $release = ($existing -join "`n") | ConvertFrom-Json
        if ($release.isDraft -or !($release.assets | Where-Object { $_.name -eq 'PriusMonitor.apk' }) -or
            !($release.assets | Where-Object { $_.name -eq 'PriusMonitor.apk.sha256' })) {
            throw 'An incomplete release exists for this APK; finish that release before retrying.'
        }
        $downloadDirectory = Join-Path $publishDirectory 'existing'
        $null = Invoke-GitHub -Arguments @('release', 'download', $tag, '--repo', $Repository, '--pattern', 'PriusMonitor.apk', '--dir', $downloadDirectory)
        $existingHash = (Get-FileHash -LiteralPath (Join-Path $downloadDirectory 'PriusMonitor.apk') -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existingHash -ne $apkHash) { throw 'Existing release content differs; no files were overwritten.' }
        $null = Invoke-GitHub -Arguments @('release', 'edit', $tag, '--repo', $Repository, '--latest')
        Write-Output $release.url
    } else {
        if (!$resolvedNotes) {
            $resolvedNotes = Join-Path $publishDirectory 'release-notes.md'
            $notes = "APK pentru Android 8.0 sau mai nou, cu aceeași semnătură pentru actualizare.`n`nSursă: $commit`nSHA-256: $apkHash`n"
            [IO.File]::WriteAllText($resolvedNotes, $notes, [Text.UTF8Encoding]::new($false))
        }
        Invoke-GitHub -Arguments @(
            'release', 'create', $tag, $canonicalApk, $checksumFile, '--repo', $Repository,
            '--target', $commit, '--latest', '--title', "$Title · $($commit.Substring(0, 7))", '--notes-file', $resolvedNotes
        )
    }
    Write-Output "https://github.com/$Repository/releases/latest/download/PriusMonitor.apk"
} finally {
    Pop-Location
}
