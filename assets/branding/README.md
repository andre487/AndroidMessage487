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
The foreground uses scale `0.73` about the center of its 1254-unit viewport; the SVGs use the
same paths, transform and gradient, cropped to Android's central 836×836 viewport.
Keep these sources synchronized when changing the artwork.
The themed launcher and notification small icon share `ic_notification.xml`, the simplified
white monitor/phone silhouette without envelopes or gradient. Update notifications use this
small icon. The app UI retains its purple Material theme and standard Material icons.

Export PNGs with the installed `rsvg-convert` utility:

```sh
for name in icon icon-round status-icon feature; do
  rsvg-convert "assets/branding/$name.svg" -o "assets/branding/$name.png"
done
rsvg-convert assets/branding/icon.svg -o fastlane/metadata/android/en-US/images/icon.png
rsvg-convert assets/branding/feature.svg -o /tmp/message487-feature.png
magick /tmp/message487-feature.png -background white -alpha remove -alpha off -type TrueColor fastlane/metadata/android/en-US/images/featureGraphic.png
```

For launcher PNGs use `rsvg-convert -w SIZE -h SIZE` with `icon.svg` or `icon-round.svg`.
Sizes by density: mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192.
SVGs are self-contained: no external images, fonts, scripts or raster embeddings.
The feature graphic is promotional artwork, not an app screenshot.

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
