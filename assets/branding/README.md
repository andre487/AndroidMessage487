# Message487 branding

[`branding.ai`](branding.ai) is the editable Illustrator master. Resource generation reads the
SVGs below; each has a matching PNG for sharing. The artwork is white monitor/phone silhouettes
on dark purple, with envelopes omitted from the monochrome status mark.

| Artboard / SVG | Size | Generated resources |
| --- | --- | --- |
| [icon](icon.svg) | 512×512 | Branding/store icon, launcher PNGs, adaptive foreground/background XML |
| [icon-round](icon-round.svg) | 512×512 | Transparent branding and round launcher PNGs |
| [status-icon](status-icon.svg) | 24×24 | Transparent PNG and `ic_notification.xml`, shared by themed launcher and update notifications |
| [feature](feature.svg) | 1024×500 | Branding banner and opaque RGB store feature graphic |

## Generate assets

From the repository root:

```sh
./scripts/generate-branding.py
# After editing the AI master, refresh SVGs before generating:
./scripts/generate-branding.py --pdf
# Alternatively, use Illustrator's SVG exporter on macOS:
./scripts/generate-branding.py --illustrator
```

The default uses existing SVGs without modifying them or launching an editor. Both AI exporters
read saved changes and preserve the master; they replace SVGs, so save direct SVG edits first.
Commit edited sources and generated SVG/PNG/XML together. Ordinary Android builds and CI use
committed resources without running this generator. To install afterwards, run
`./scripts/run-without-debugging.sh`; installation alone does not regenerate artwork.

Generation needs Python 3, `rsvg-convert`, ImageMagick's `magick`, JDK 21, a configured Android SDK
and Gradle dependencies cached by a normal build. Android SVG conversion runs offline.
PDF extraction additionally needs Poppler's `pdfinfo` and `pdftocairo` on `PATH`
(`brew install poppler` or `apt install poppler-utils`). With Xpdf installed, use
`brew install --skip-link poppler` and prepend `$(brew --prefix poppler)/bin` to `PATH`.
Illustrator export requires macOS, Illustrator and Automation access when prompted.
See `--help` for options; paths resolve relative to the repository, not the calling directory.

Inspect output without overwriting repository assets:

```sh
./scripts/generate-branding.py --pdf --output-root /tmp/message487-branding
```

## Artwork requirements

- Keep AI artboards in the table's order and dimensions. PDF import maps by page order;
  Illustrator export checks names. Enable PDF compatibility when saving AI. Keep Illustrator
  artwork visible/unlocked; symbols are expanded only in temporary export documents.
- SVGs must be self-contained vectors, without external images/fonts/scripts or embedded rasters.
  PDF extraction rejects raster images. Android's SVG importer rejects unsupported features
  before repository outputs are written. Layer names and IDs are unrestricted.
- In `icon.svg`, the first painted shape is the background; keep it as a single full-artboard
  shape behind the foreground. Its positive square `viewBox` defines the visible icon area.
  Round artwork has independent layout/clipping; transparent margins are preserved.
- Adaptive layers use a 1.5× viewport. Full-artboard rectangular clips expand for overscan;
  internal clips remain. Viewport translations preserve fractional paths, but source transforms
  can introduce Android-importer rounding. PNGs render directly at each target size without
  intermediate resizing. Geometry is not snapped to pixels; SVG/Android antialiasing can differ.
- PDF and Illustrator SVG exports can differ, including envelope stroke thickness. Compare
  outputs after switching. Illustrator exports outline text, use presentation attributes and
  normalize random IDs for repeatable diffs; export details live in
  [the generator](../../scripts/generate-branding.py) and [JSX](../../scripts/export-branding.jsx).

Generated Android layers are in `app/src/main/res/drawable/`; adaptive-icon and monochrome-inset
XML references stay static. Launcher density sizes are defined by the generator. Round/status
PNGs retain transparency; the white status mark needs a contrasting background.

## Samsung preview

```sh
python3 scripts/preview-samsung-icon.py --source assets/branding/icon.svg /tmp/message487-samsung.png
python3 scripts/preview-samsung-icon.py --transparent /tmp/message487-samsung-transparent.png
```

Regenerate after AI edits before previewing. Use an `.svg` output for a vector preview;
PNG output needs `rsvg-convert`. For size, background and bevel controls, see `--help`.
The source `viewBox` defines mask bounds; the default source shares the adaptive foreground scale.

The rounded-square mask, shadow and artwork scale approximate the supplied Samsung reference,
using [SemAppIconSolution](https://github.com/488315/samsung_framework/blob/master/framework/sources/android/app/SemAppIconSolution.java).
SVG blur and the bevel are approximations; themes, night tint and animations are excluded.
For an exact-device comparison, use [One UI Icon Extractor](https://github.com/Palmoe/OneUI-Icon-Extractor)
on the target Samsung device. The preview is not a launcher resource or proof of an exact match.

## Verification

```sh
python3 -m unittest discover -s scripts/tests -p test_generate_branding.py
python3 -m unittest discover -s scripts/tests -p test_preview_samsung_icon.py
```

Default checks cover SVG preparation, command routing and, when Poppler is available, real PDF
extraction, page sizes, repeatability and master preservation. Enable Android/PNG checks with:

```sh
MESSAGE487_TEST_ANDROID_VECTORS=1 python3 -m unittest discover -s scripts/tests -p test_generate_branding.py
```

CI enables Android conversion after populating the Gradle cache; PNG checks also need the raster
tools above. To exercise live Illustrator export and master preservation on macOS, use
`MESSAGE487_TEST_ILLUSTRATOR=1` with the same test command.

## Store listing

[Fastlane metadata](../../fastlane/metadata/android) is the canonical store content for both locales,
shared by F-Droid and Google Play. Upload [icon.png](../../fastlane/metadata/android/en-US/images/icon.png)
and [featureGraphic.png](../../fastlane/metadata/android/en-US/images/featureGraphic.png) for Play.

Maintain these settings manually in Google Play Console:

| Setting | Value |
| --- | --- |
| App or game | App (Приложение) |
| Category | Tools (Инструменты) |
| Tags | Home automation (Автоматизация дома), Tools (Инструменты) |
| Phone screenshot order, both locales | `1.png`, `2.png`, `3.png` |

Screenshots in `fastlane/metadata/android/{en-US,ru-RU}/images/phoneScreenshots/` are separate
1080×1920 captures from the API 35 debug emulator with synthetic data and app locales.
Refresh them from the submitted release when UI changes; generated illustrations and the feature
banner are not app screenshots. Follow [Google Play asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

## Download badge

[get-it-on-github.svg](get-it-on-github.svg) is the vector source for the GitHub download
badge. It uses DejaVu Sans for “GET IT ON”, Roboto Slab Bold for “GitHub”, and the
GitHub mark from Primer Octicons. Text is outlined so rendering requires no installed fonts.
Third-party notices are in [get-it-on-github-NOTICES.txt](get-it-on-github-NOTICES.txt).

Regenerate the PNG used in the README and installation guides:

```sh
rsvg-convert -w 1292 -h 500 assets/branding/get-it-on-github.svg -o assets/branding/get-it-on-github.png
```
