# Builds the universal jar (OCR engine and natives for Windows/macOS/Linux included) and runs the self-test.
# Needs JDK 17+ and Maven.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
mvn -B -q package
if ($LASTEXITCODE -ne 0) { throw "Build failed" }
java -jar target\isin-snap.jar --selftest
if ($LASTEXITCODE -ne 0) { throw "Self-test failed" }
Write-Host "OK -> target\isin-snap.jar"
