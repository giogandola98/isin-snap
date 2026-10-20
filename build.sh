#!/usr/bin/env bash
# Build without Maven: only a JDK 17+ is needed.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
javac --release 17 -encoding UTF-8 -d build/classes $(find src/main/java -name '*.java')
jar --create --file build/isin-snap.jar --main-class isinsnap.IsinSnap -C build/classes .
java -jar build/isin-snap.jar --selftest
echo "OK -> build/isin-snap.jar"
