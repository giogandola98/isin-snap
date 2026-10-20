# ISIN Snap

An always-on-top button: click it, select an area of the screen (for example a Bloomberg chat) and get
the list of ISINs it contains, de-duplicated and ready to copy.

▶ **[Watch the 60-second demo](docs/demo.mp4)**

- **Java 17+, zero dependencies.** OCR is done by [Tesseract](https://github.com/tesseract-ocr/tesseract),
  called as an executable: no native libraries to load into Java, same behaviour on Windows, macOS and Linux.
- **Real validation.** Every ISIN is checked for ISO country prefix, format and check digit.
- **OCR error correction**, with a clear indication of how much to trust each result (see below).
- **De-duplication.** One row per ISIN, with a count of how many times it appears in the capture.
  ISINs you already copied in the same session are flagged and not pre-checked.

## Download

Ready-made builds are attached to the [GitHub Releases](../../releases) (`isin-snap.jar`).
Every `v*` tag is built, self-tested and published automatically by GitHub Actions.

## Requirements

- Java 17 or newer.
- Tesseract 5:

| OS | Tesseract |
|---|---|
| Windows | UB Mannheim installer (`tesseract-ocr-w64-setup-5.x.exe`), or a portable copy: a `tesseract\` folder with `tesseract.exe` and `tessdata\eng.traineddata` next to `isin-snap.jar` |
| macOS | `brew install tesseract`, then on first run grant *Screen Recording* to Java/your terminal (System Settings → Privacy & Security) |
| Linux | `sudo apt install tesseract-ocr` |

The app looks for Tesseract in this order: `-Dtesseract.path=…`, the `TESSERACT_PATH` variable,
a `tesseract/` folder next to the jar, `PATH`, standard install locations (Homebrew included).

## Build and run

```bash
./build.sh                 # or .\build.ps1 on Windows, or: mvn package
java -jar build/isin-snap.jar
```

Other commands:

```bash
java -jar isin-snap.jar --file screenshot.png              # analyse an image and print the ISINs
java -Disinsnap.debug=true -jar isin-snap.jar --file x.png # also print the raw OCR text
java -jar isin-snap.jar --selftest                         # check the recognition logic
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
| Read | read as-is, check digit correct | yes |
| Corrected (safe fix) | deterministic fixes: `1T…`→`IT…`, last digit read as a letter, `O`/`I` in the body → `0`/`1` (WKN, SEDOL and CUSIP never use those letters) | yes |
| Corrected: please check | valid only after changing an ambiguous body character (e.g. `8`↔`B`) | no |
| N variants: choose | several corrections are equally valid, or two OCR passes read different valid ISINs | no |
| … · already copied | already copied earlier in this session | no |

Why the caution: the check digit is *one* digit of redundancy. It catches almost every single error,
but if you try many corrections, one in ten comes back valid by chance. In testing, freely correcting
the body produced around 15% wrong ISINs presented as good ones. That is why body corrections are only
suggested, never applied silently.

## How it works

```
button → RegionSelector (frozen screenshot, physical pixels even on HiDPI/Retina)
       → IsinScanner: several Tesseract passes in parallel (upscaling, dark themes inverted)
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
| Found and pre-checked | 95.6% |
| Found as "needs review" | 3.4% |
| Missed | 1.0% |
| **Wrong ISINs presented as confident** | **0** |
| Spurious suggestions (not pre-checked) | 6 across 48 screenshots |
| Average scan time | ~3.8 s on 2 cores (the 4 passes run in parallel: more cores, less time) |

`samples/chat-bloomberg-simulated.png` is one of the test screenshots: `--file` finds 11 ISINs out of 11.

These are simulated screenshots: the real Bloomberg font is different. The first thing to do is run
`--file` on a few real chat screenshots and, if needed, retune `IsinScanner.DEFAULT_PASSES`.

## Known limitations

- **Multi-monitor**: the screen captured is the one the mouse is on when you click the button.
- **Linux Wayland**: capture goes through the system portal (needs JDK 21+ and a permission).
  X11 works directly.
- **macOS**: without the *Screen Recording* permission the screenshot only contains the wallpaper.
- **Corporate laptops**: check that installing Tesseract (or keeping a portable copy) and capturing
  the screen are allowed by your policies.
- The "already copied" list lives in memory and is cleared when the app restarts.

## Packaging with a bundled JRE

```bash
jpackage --name "ISIN Snap" --input build --main-jar isin-snap.jar \
         --main-class isinsnap.IsinSnap --type app-image
```

On Windows the `tesseract\` folder can go inside the app-image: a single folder to copy, nothing to install.

## Releasing

```bash
git tag v0.2.0 && git push origin v0.2.0
```

The `Release` workflow builds the jar with Java 17, runs `--selftest` and creates the GitHub Release with
`isin-snap.jar` and the demo video attached.
