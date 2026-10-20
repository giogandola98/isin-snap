# Build without Maven on Windows: only a JDK 17+ is needed.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
Remove-Item -Recurse -Force build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build\classes | Out-Null
$sources = Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { $_.FullName }
javac --release 17 -encoding UTF-8 -d build\classes $sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }
jar --create --file build\isin-snap.jar --main-class isinsnap.IsinSnap -C build\classes .
java -jar build\isin-snap.jar --selftest
if ($LASTEXITCODE -ne 0) { throw "Self-test failed" }
Write-Host "OK -> build\isin-snap.jar"
