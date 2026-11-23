# Message487 branding

Edit [branding.svg](branding.svg) in Inkscape. The Android and store resource generator
reads this master directly. Each page and its top-level layer share a name.

| Page | Source size | Use |
| --- | --- | --- |
| icon | 500×500 | Adaptive launcher layers and 512×512 store icon |
| icon-round | 500×500 | [Standalone round artwork](icon-round.svg) for the README |
| status-icon | 32×32 | 24 dp notification vector and themed launcher icon |
| feature | 1024×500 | Store feature graphic |

## Generate assets

From the repository root, run:

```sh
uv run scripts/generate-branding.py
```

This refreshes the Android VectorDrawables and the two opaque store PNGs.
Use `--output-root /tmp/message487-branding` to inspect a separate output tree.
To refresh the README icon, export the pages into a temporary output tree and copy the round icon:

```sh
uv run scripts/generate-branding.py --export-pages --output-root /tmp/message487-branding
cp /tmp/message487-branding/assets/icon-round.svg assets/icon-round.svg
```

Commit the master and affected resources together. Android builds use the committed XML.

Keep the named groups `background` and `foreground` inside the `icon` layer.
The background fills the 108 dp adaptive canvas. The foreground occupies its central
72 dp, leaving room for launcher masks and motion. Both launcher entries share these
vectors on Android 8.0 and later. The round and feature pages may clone the icon artwork.
Keep artwork self-contained and use vector paths supported by Android's SVG importer.

Generation requires Python 3, `rsvg-convert`, ImageMagick's `magick`, JDK 21, a configured
Android SDK and Gradle dependencies cached by a normal build. The vector converter runs offline.

## Verification

```sh
uv run python -m unittest discover -s scripts/tests -p test_generate_branding.py
```

The tests check page names, linked artwork and separation of the Android layers.
Enable Android conversion and PNG output checks after populating the Gradle cache:

```sh
MESSAGE487_TEST_ANDROID_VECTORS=1 uv run python -m unittest discover -s scripts/tests -p test_generate_branding.py
```

## Store listing

[Fastlane metadata](../fastlane/metadata/android) is the canonical store content for both locales,
shared by F-Droid and Google Play. Upload [icon.png](../fastlane/metadata/android/en-US/images/icon.png)
and [featureGraphic.png](../fastlane/metadata/android/en-US/images/featureGraphic.png) for Play.

Maintain these settings manually in Google Play Console:

| Setting | Value |
| --- | --- |
| App or game | App |
| Category | Tools |
| Tags | Home automation, Tools |
| Phone screenshot order, both locales | `1.png`, `2.png`, `3.png` |

Screenshots in `fastlane/metadata/android/{en-US,ru-RU}/images/phoneScreenshots/` are separate
1080×1920 captures from the API 35 debug emulator with synthetic data and app locales.
Refresh them from the submitted release when UI changes; generated illustrations and the feature
banner are not app screenshots. Follow [Google Play asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).

## Download badge

[get-it-on-github.svg](get-it-on-github.svg) is the vector source for the GitHub download
badge. It uses DejaVu Sans for “GET IT ON”, Roboto Slab Bold for “GitHub”, and the
GitHub mark from Primer Octicons. Text is outlined so rendering requires no installed fonts.

Regenerate the PNG used in the README and installation guides:

```sh
rsvg-convert -w 1292 -h 500 assets/get-it-on-github.svg -o assets/get-it-on-github.png
```

## Licenses

The Message487 artwork in [branding.svg](branding.svg) and its icon and banner exports use
[Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/).
The master and standalone SVG exports carry the license in their metadata.
When sharing the artwork, credit the source, link to the license and indicate any changes.

The GitHub download badge uses third-party artwork and outlined fonts. The
[badge notices](get-it-on-github-NOTICES.txt) cover Primer Octicons, Roboto Slab and DejaVu Sans.
The generator and application code use the repository's [MIT License](../LICENSE).
