#!/usr/bin/env python3
"""Copy the universal jar keeping only the native libraries of one platform.

Used when building the per-OS bundles: a Windows bundle has no use for macOS/Linux natives.

    python tools/slim_jar.py target/isin-snap.jar out/isin-snap.jar windows-x86_64
"""
import sys
import zipfile

PLATFORMS = ["windows-x86_64", "macosx-x86_64", "macosx-arm64", "linux-x86_64", "linux-arm64"]


def keep(name: str, platform: str) -> bool:
    parts = name.split("/")
    # org/bytedeco/<lib>/<platform>/...  and  META-INF/native-image/<platform>/...
    if len(parts) > 3 and parts[0] == "org" and parts[1] == "bytedeco" and parts[3] in PLATFORMS:
        return parts[3] == platform
    if len(parts) > 2 and parts[0] == "META-INF" and parts[1] == "native-image" and parts[2] in PLATFORMS:
        return parts[2] == platform
    return True


def main() -> None:
    src, dst, platform = sys.argv[1:4]
    if platform not in PLATFORMS:
        sys.exit(f"unknown platform {platform}; expected one of {PLATFORMS}")
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if keep(item.filename, platform):
                zout.writestr(item, zin.read(item.filename))
    print(f"{dst}: natives for {platform} only")


if __name__ == "__main__":
    main()
