# Message487 branding

| Asset | Use |
| --- | --- |
| [`icon.png`](../../fastlane/metadata/android/en-US/images/icon.png) | Google Play app icon, 512×512 RGBA PNG; also used in README |
| [`featureGraphic.png`](../../fastlane/metadata/android/en-US/images/featureGraphic.png) | Google Play feature graphic, 1024×500 RGB PNG without alpha; shared by both locales |
| `message487-feature-master.png` | Original generated banner, retained for future exports |
| `fastlane/metadata/android/{en-US,ru-RU}/images/phoneScreenshots/` | Actual phone screenshots in English and Russian, 1080×1920 |

Launcher PNGs and the adaptive foreground are copied from
[andre487/sms487](https://github.com/andre487/sms487/tree/d4aca0724c4d8c8cfcfe128c6df6cc93f64625f2/client/app/src/main).
The monitor, star, phone and indigo background preserve the original app identity.
The existing vector foreground also supplies the Android themed-icon mask; round launcher
resources and legacy notification icons are included. The app UI retains its purple Material theme.
The original artwork is distributed under the [sms487 MIT license](sms487-LICENSE).

The feature illustration was produced with the built-in `image_gen` tool using the old
Play Store icon as its reference, then exported with `sips -z 500 1024` as an opaque PNG.
The generation prompt is recorded in [feature-prompt.txt](feature-prompt.txt).
It extends the icon's device imagery with notification cards and workflow nodes and uses
no localized text or third-party service marks. It is promotional artwork, not an app screenshot.

Current store assets and localized descriptions have one canonical location under
[`fastlane/metadata/android`](../../fastlane/metadata/android), shared by F-Droid and Google Play.

For a Play listing, upload [`icon.png`](../../fastlane/metadata/android/en-US/images/icon.png) as the app icon and [`featureGraphic.png`](../../fastlane/metadata/android/en-US/images/featureGraphic.png)
as the feature graphic. The screenshots were captured from the debug app on the API 35 emulator with synthetic
local data, using Android app locales and a 1080×1920 display. Refresh them from the release
being submitted whenever its UI changes; do not use generated illustrations as screenshots. Follow the current
[Google Play asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en).
