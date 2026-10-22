# ISIN Snap

An always-on-top button: click it, select an area of the screen (for example a Bloomberg chat) and get
the list of ISINs it contains, de-duplicated and ready to copy.

▶ **[Watch the 60-second demo](docs/demo.mp4)**

- **Nothing to install.** The OCR engine ([Tesseract](https://github.com/tesseract-ocr/tesseract) 5.5, via
  [JavaCPP](https://github.com/bytedeco/javacpp-presets/tree/master/tesseract)) and its English model are inside
  the app, for Windows, macOS and Linux. The per-OS downloads even include their own Java.
- **Real validation.** Every ISIN is checked for ISO country prefix, format and check digit.
- **OCR error correction**, with a clear indication of how much to trust each result (see below).
- **De-duplication.** One row per ISIN, with a count of how many times it appears in the capture.
  ISINs you already copied in the same session are flagged and not pre-checked.

## Download

From the [Releases](../../releases) page (`main-latest` = latest build of `main` that passed all tests):

| File | What you need | How to run |
|---|---|---|
| `ISINSnap-windows-x64.zip` | nothing | unzip, run `ISINSnap\ISINSnap.exe` |
| `ISINSnap-macos-arm64.zip` | nothing (Apple Silicon) | unzip, right-click `ISINSnap.app` → Open the first time |
| `ISINSnap-linux-x64.tar.gz` | nothing | extract, run `ISINSnap/bin/ISINSnap` |
| `isin-snap.jar` | Java 17+ | `java -jar isin-snap.jar` (one file for Windows x64, macOS Intel/Apple Silicon, Linux x64/arm64) |

On first launch the native libraries are unpacked to `~/.javacpp/cache` and the OCR model to
`~/.isin-snap/tessdata` (falls back to the temp folder if the home folder is not writable). If a corporate
policy blocks DLLs in the user profile, point the cache elsewhere with
`-Dorg.bytedeco.javacpp.cachedir=<folder>`.

macOS: on first use grant *Screen Recording* (System Settings → Privacy & Security), otherwise the
capture only contains the wallpaper. The apps are not code-signed: Windows SmartScreen and macOS
Gatekeeper may warn the first time.

## Build

Needs JDK 17+ and Maven (the build downloads the OCR libraries from Maven Central).

```bash
./build.sh                 # or .\build.ps1 on Windows, or: mvn package
java -jar target/isin-snap.jar
```

Other commands:

```bash
java -jar isin-snap.jar --file screenshot.png              # analyse an image and print the ISINs
java -Disinsnap.debug=true -jar isin-snap.jar --file x.png # also print the raw OCR text
java -jar isin-snap.jar --selftest                         # check the recognition logic
java -jar isin-snap.jar --version                          # embedded OCR engine version
```

## Usage

1. Click **Scan ISIN** (floating button at the bottom right; drag it by the blue handle on its left).
   There is also a system tray icon where the OS supports it. Right-click → Quit.
2. The screen freezes: drag a rectangle over the chat. `Esc` or right-click cancels.
3. The results window opens:
   - **confident** ISINs are already checked; *Copy checked* puts them on the clipboard, one per line;
   - ISINs that **need review** are not checked: look at them in the *Image* tab and tick them by hand;
   - for **ambiguous** ones, click the ISIN and pick the right variant from the drop-down;
   - the **Count** column shows how many times an ISIN appears in the capture (duplicates are merged);
   - ISINs **already copied** earlier in the session are greyed out and not checked;
   - double-click a row to copy that single ISIN.

### Statuses

| Status | Meaning | Pre-checked |
|---|---|---|
| Read | read as-is by at least two OCR passes, check digit correct | yes |
| Corrected (safe fix) | deterministic fixes: `1T…`→`IT…`, last digit read as a letter, `O`/`I` in the body → `0`/`1` (WKN, SEDOL and CUSIP never use those letters) | yes |
| Corrected: please check | valid only after changing an ambiguous body character (e.g. `8`↔`B`), read by a single OCR pass, or with 5+ letters in the body | no |
| N variants: choose | several corrections are equally valid, or two OCR passes read different valid ISINs | no |
| … · already copied | already copied earlier in this session | no |

Why the caution: the check digit is *one* digit of redundancy. It catches almost every single error,
but if you try many corrections, one in ten comes back valid by chance. In testing, freely correcting
the body produced around 15% wrong ISINs presented as good ones. That is why body corrections are only
suggested, never applied silently, and why an ISIN must be read by at least two of the four OCR passes
to be pre-checked.

## How it works

```
button → RegionSelector (frozen screenshot, physical pixels even on HiDPI/Retina)
       → IsinScanner: 4 embedded Tesseract engines in parallel (upscaling, dark themes inverted)
       → IsinExtractor: candidates → validation → corrections → merge passes → de-duplicate
       → ResultWindow
```

- **Preprocessing** (`ImagePrep`): grayscale, automatic inversion of dark themes (the Bloomberg chat is
  light text on black), bicubic upscaling, white border.
- **Several OCR passes** (`IsinScanner.DEFAULT_PASSES`): merged; readings that agree confirm each other,
  and readings that disagree become "needs review" instead of a silent error.
- **Chat text** (`IsinExtractor`): handles lowercase (`it0005…`), punctuation (`IT…/IT…`,
  `(ISIN: XS…)`), ISINs glued to other words or split in two by the OCR. Prices, sizes and currency
  codes are never joined into fake ISINs.

## Test results

Measured on 48 simulated Bloomberg IB chat screenshots: black background, white or amber text,
11-14 px fonts, also at Retina 2x, ISINs in upper and lower case, separated by `/` or commas or in
parentheses, mixed with prices, sizes and times. 498 ISINs in total.

| | |
|---|---|
| Found and pre-checked | 94.4% |
| Found as "needs review" | 4.8% |
| Missed | 0.8% |
| **Wrong ISINs presented as confident** | **0** |
| Spurious suggestions (not pre-checked) | 7 across 48 screenshots |
| Average scan time | ~1.3 s on 2 cores (engines stay loaded; the 4 passes run in parallel) |

`samples/chat-bloomberg-simulated.png` is one of the test screenshots: `--file` finds 11 ISINs out of 11.

These are simulated screenshots: the real Bloomberg font is different. The first thing to do is run
`--file` on a few real chat screenshots and, if needed, retune `IsinScanner.DEFAULT_PASSES`.

## Known limitations

- **Multi-monitor**: the screen captured is the one the mouse is on when you click the button.
- **Linux Wayland**: capture goes through the system portal (needs JDK 21+ and a permission).
  X11 works directly.
- **macOS**: without the *Screen Recording* permission the screenshot only contains the wallpaper.
- **Corporate laptops**: nothing is installed, but check that running the app and capturing the screen
  are allowed by your policies (see the note on native libraries under *Download*).
- **Platforms**: Windows on ARM is not supported by the embedded OCR libraries. The macOS bundle is for
  Apple Silicon; Intel Macs can use the jar with Java 17+.
- The "already copied" list lives in memory and is cleared when the app restarts.

## CI and releases

`.github/workflows/build.yml`, on every push and pull request to `main`:

1. builds the universal jar;
2. tests it on **Windows, macOS and Linux runners without Tesseract installed** (self-test + OCR on the
   sample screenshot), which proves nothing external is needed;
3. if all tests pass, builds the per-OS bundles with `jpackage` and smoke-tests each one;
4. publishes everything: the `main-latest` pre-release for pushes to `main`, a versioned release for tags.

```bash
git tag v0.3.0 && git push origin v0.3.0
```

`tools/slim_jar.py` strips the other platforms' natives from each bundle.
