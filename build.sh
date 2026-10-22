#!/usr/bin/env bash
# Builds the universal jar (OCR engine and natives for Windows/macOS/Linux included) and runs the self-test.
# Needs JDK 17+ and Maven.
set -euo pipefail
cd "$(dirname "$0")"
mvn -B -q package
java -jar target/isin-snap.jar --selftest
echo "OK -> target/isin-snap.jar"
