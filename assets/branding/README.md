# Message487 branding

| Asset | Use |
| --- | --- |
| [icon.svg](icon.svg) · [PNG](icon.png) | Canonical square artwork for web and PNG exports |
| [icon-round.svg](icon-round.svg) · [PNG](icon-round.png) | Circular artwork used in the project README and round launcher PNGs |
| [status-icon.svg](status-icon.svg) · [PNG](status-icon.png) | Simplified monochrome monitor/phone mark for small sizes |
| [feature.svg](feature.svg) · [PNG](feature.png) | Canonical store banner artwork, 1024×500 |
| [icon.png](../../fastlane/metadata/android/en-US/images/icon.png) | Store app icon, 512×512 RGB PNG |
| [featureGraphic.png](../../fastlane/metadata/android/en-US/images/featureGraphic.png) | Store feature graphic, 1024×500 opaque RGB PNG; shared by both locales |
| `fastlane/metadata/android/{en-US,ru-RU}/images/phoneScreenshots/` | Actual phone screenshots in English and Russian, 1080×1920 |

The selected design shows an open envelope on a monitor and a closed envelope on a phone,
with a cyan/blue/violet/magenta gradient inspired by AndroidMegaProxy. The initial concepts
were created with the built-in `image_gen` tool, then reconstructed as native vectors.
The final design prompt was: “Monitor with a small open envelope and phone with a small closed
envelope on a cyan/blue/violet/magenta gradient; preserve generous margins in a circular crop.”
Each SVG has a matching PNG beside it for sharing where SVG is unsupported.
Sizes: square/round icon 512×512, status icon 24×24, feature graphic 1024×500.
The round and status PNGs retain transparency; the white status mark needs a contrasting background.

Android adaptive layers live in `app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`.
The adaptive foreground and round SVG use scale `0.65` about the center of their 1254-unit
viewport for extra circular-mask padding. The square SVG retains scale `0.73`.
Both SVGs share the Android paths and gradient, cropped to the central 836×836 viewport.
The SVG files remain separate editable sources; regenerate derived files after editing them.
The themed launcher and notification small icon share `ic_notification.xml`, the simplified
white monitor/phone silhouette without envelopes or gradient. Update notifications use this
small icon. The app UI retains its purple Material theme and standard Material icons.

Generate all derived assets from the **separate** SVG sources:

```sh
python3 scripts/generate-branding.py
```

Requires `rsvg-convert` and ImageMagick's `magick`. Run from any directory.
Use `--output-root /tmp/message487-branding` to inspect exports without overwriting repository files.

| Source | Generated assets |
| --- | --- |
| `icon.svg` | Square branding PNG, opaque RGB store icon, square launcher PNGs |
| `icon-round.svg` | Transparent round branding PNG, round launcher PNGs, adaptive foreground/background XML |
| `status-icon.svg` | Transparent 24×24 PNG and `ic_notification.xml` |
| `feature.svg` | Branding banner PNG and opaque RGB store feature graphic |

Launcher PNG sizes: mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192.
The script preserves each source's layout, including the square/round scale difference.
The Android vector exporter supports the existing path fill/stroke attributes, the round
artwork's `translate(x y) scale(s)` transform, and its diagonal background gradient.
Unsupported path features or gradient changes cause an error instead of silently losing details.
Adaptive-icon and monochrome-inset XML files only reference these generated drawables and
remain static. Samsung previews are optional and use the separate command below.

SVGs are self-contained: no external images, fonts, scripts or raster embeddings.
The feature graphic is promotional artwork, not an app screenshot.

Preview the adaptive artwork with an approximate Samsung-style rounded-square mask:

```sh
python3 scripts/preview-samsung-icon.py --source assets/branding/icon-round.svg /tmp/message487-samsung.png
# Edit the source SVG, then rerun the same command to regenerate the PNG.
# Use an .svg output path instead to generate a masked vector preview.
```

Output defaults to 140×140 pixels, approximately the icon size in the supplied screenshot.
Use `--size 512` for a larger preview.
SVG output uses only Python's standard library; PNG output also needs `rsvg-convert`.
Use `--source` to choose another SVG; its `viewBox` defines the mask bounds.
By default the preview reuses `icon-round.svg`, which shares the adaptive foreground's scale and
position. Keep it synchronized with the Android layers as described above. The mask
approximates the supplied Samsung screenshot. Shadow radii (1/96 and 1/48 of icon size),
alpha (41/255 and 26/255), and 0.96 artwork scale follow the published
[SemAppIconSolution implementation](https://github.com/488315/samsung_framework/blob/master/framework/sources/android/app/SemAppIconSolution.java).
SVG Gaussian blur is not identical to Android's Skia shadow rendering. The inner highlight
and shade are a visual approximation, not a recovered Samsung bevel algorithm.
Use `--bevel 0.3` to adjust edge strength (0–1), or `--flat` for the mask alone.
Themes, night-mode tint and animations are not reproduced.

For a device-rendered reference, [One UI Icon Extractor](https://github.com/Palmoe/OneUI-Icon-Extractor)
uses Samsung's `semGetApplicationIconForIconTray` / `semGetActivityIconForIconTray` APIs
on a Samsung device, retaining One UI effects. Compare its export from the target phone
with this preview before claiming an exact match. This is a preview, not another Android
launcher resource.

Earlier artwork originated from
[andre487/sms487](https://github.com/andre487/sms487/tree/d4aca0724c4d8c8cfcfe128c6df6cc93f64625f2/client/app/src/main)
under the [sms487 MIT license](sms487-LICENSE); the original license is retained.
Store graphics and localized descriptions have one canonical location under
[fastlane/metadata/android](../../fastlane/metadata/android), shared by F-Droid and Google Play.

## Google Play store settings

These settings are maintained manually in Google Play Console:

| Setting | Value |
| --- | --- |
| App or game | App (Приложение) |
| Category | Tools (Инструменты) |
| Tags | Home automation (Автоматизация дома), Tools (Инструменты) |
| Phone screenshot order, both locales | `1.png`, `2.png`, `3.png` |

For a Play listing, upload [icon.png](../../fastlane/metadata/android/en-US/images/icon.png)
and [featureGraphic.png](../../fastlane/metadata/android/en-US/images/featureGraphic.png).
The screenshots were captured from the debug app on the API 35 emulator with synthetic
local data, using Android app locales and a 1080×1920 display. Refresh them from the release
being submitted whenever its UI changes; do not use generated illustrations as screenshots.
Follow the current [Google Play asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).
