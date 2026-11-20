# Builds ChromaGrid.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-Install]
param([switch]$Install)
$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.0"
$androidJar = "$sdk\platforms\android-35\android.jar"
$app = "$root\app"
$out = "$root\build"
$apk = "$root\ChromaGrid.apk"
$keystore = "$root\debug.keystore"

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Write-Host "== aapt2 compile/link"
Run "$bt\aapt2.exe" @("compile", "--dir", "$app\res", "-o", "$out\res.zip")
Run "$bt\aapt2.exe" @("link", "-I", $androidJar, "--manifest", "$app\AndroidManifest.xml",
    "--min-sdk-version", "26", "--target-sdk-version", "35",
    "--version-code", "1", "--version-name", "1.0",
    "--java", "$out\gen", "-o", "$out\unsigned.apk", "$out\res.zip")

Write-Host "== javac"
$sources = Get-ChildItem -Recurse "$app\src", "$out\gen" -Filter *.java | ForEach-Object { $_.FullName }
Run "javac" (@("--release", "11", "-nowarn", "-encoding", "UTF-8", "-cp", $androidJar, "-d", "$out\classes") + $sources)

Write-Host "== d8"
$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object { $_.FullName }
Run "$bt\d8.bat" (@("--release", "--min-api", "26", "--lib", $androidJar, "--output", "$out\dex") + $classes)

Write-Host "== package"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()
Run "$bt\zipalign.exe" @("-f", "-p", "4", "$out\unsigned.apk", "$out\aligned.apk")

if (-not (Test-Path $keystore)) {
    Write-Host "== creating signing key ($keystore)"
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if (-not $keytool) { $keytool = (Get-ChildItem "C:\Program Files\Java\*\bin\keytool.exe" | Select-Object -First 1).FullName }
    Run $keytool @("-genkeypair", "-keystore", $keystore, "-storepass", "android", "-keypass", "android",
        "-alias", "chromagrid", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Chroma Grid")
}
Run "$bt\apksigner.bat" @("sign", "--ks", $keystore, "--ks-pass", "pass:android",
    "--ks-key-alias", "chromagrid", "--out", $apk, "$out\aligned.apk")
Write-Host "== built $apk"

if ($Install) {
    Run "$sdk\platform-tools\adb.exe" @("install", "-r", $apk)
}
