$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;" + $env:PATH
$sdk = "D:\AndroidSDK"
$bt = "$sdk\build-tools\34.0.0"
$androidJar = "$sdk\platforms\android-34\android.jar"
$build = Join-Path $root "build"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\out" | Out-Null

Write-Host "[1/6] aapt2 compile"
& "$bt\aapt2.exe" compile --dir (Join-Path $root "res") -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] aapt2 link"
& "$bt\aapt2.exe" link -o "$build\base.apk" -I $androidJar --manifest (Join-Path $root "AndroidManifest.xml") "$build\res.zip" --auto-add-overlay --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] javac"
$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath $androidJar -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] d8 dex"
& jar cf "$build\classes.jar" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$bt\d8.bat" --release --lib $androidJar --output "$build\out" "$build\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] package + zipalign"
$staticDir = Join-Path (Split-Path -Parent $root) "static"
New-Item -ItemType Directory -Force -Path "$build\assets" | Out-Null
Copy-Item (Join-Path $staticDir "index.html") "$build\assets\index.html" -Force
Copy-Item "$build\base.apk" "$build\unsigned.apk" -Force
Push-Location "$build\out"
& jar uf "$build\unsigned.apk" classes.dex
$code1 = $LASTEXITCODE
Pop-Location
if ($code1 -ne 0) { throw "add dex failed" }
Push-Location $build
& jar uf "$build\unsigned.apk" assets/index.html
$code2 = $LASTEXITCODE
Pop-Location
if ($code2 -ne 0) { throw "add assets failed" }
& "$bt\zipalign.exe" -p 4 "$build\unsigned.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] sign"
$ks = Join-Path $root "debug.jks"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias k -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ledger, O=local"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out (Join-Path $root "app.apk") "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
& "$bt\apksigner.bat" verify (Join-Path $root "app.apk")
if ($LASTEXITCODE -ne 0) { throw "verify failed" }

$apk = Join-Path $root "app.apk"
Write-Host ("DONE " + $apk + " " + [math]::Round((Get-Item $apk).Length / 1MB, 2) + " MB")
