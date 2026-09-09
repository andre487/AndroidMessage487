# Install Message487 from an APK

[English](../en/apk-installation.md) | [Русский](../ru/apk-installation.md)

## Download and install

1. Open the project's [GitHub Releases](https://github.com/andre487/AndroidMessage487/releases)
   on your phone. Choose a release, read its notes and download `message487-<version>.apk`
   from **Assets**. Source-code archives and `mapping.txt` cannot be installed.
2. Open the downloaded APK. If Android asks for permission to install from this source,
   open **Settings** from that dialog and enable **Allow from this source** for the
   browser or file manager opening the APK. Return and select **Install**.
3. Open Message487 once. You can then revoke the installation permission from that
   browser/file manager; it is not needed for forwarding.

The app requires Android 8.0 or newer. Use the APK attached to the project release,
not a repackaged copy. Release assets include `SHA256SUMS` for checking file integrity.
Menu names vary by Android version and manufacturer.

## Identify the blocking screen

| What you see | Next step |
| --- | --- |
| Installation from this source is not allowed | Grant the browser/file manager permission as above |
| Play Protect suggests scanning an unknown app | Run the offered scan and follow its result |
| Play Protect blocks installation because the app requests sensitive data | Read the Play Protect section below |
| The app is installed, but notification access says “Restricted setting” | Allow restricted settings for Message487 as described below |
| A warning specifically mentions an unverified developer | Follow Google's [developer verification instructions](https://support.google.com/android/answer/17588095?hl=en); this is a separate check |
| Installation/settings are controlled by an administrator | Contact the device or work-profile administrator |

## Play Protect blocks installation

Message487 receives new SMS (`RECEIVE_SMS`) and can read other apps' notifications
through a notification listener. These capabilities are necessary for forwarding,
but also appear in Google's sensitive-permission installation checks for apps downloaded
from the internet. Such a warning can therefore be related to these permissions;
it does not establish the exact cause of a particular block. Google also distinguishes
this warning from a harmful-app detection. See [Google's warning descriptions](https://developers.google.com/android/play-protect/warning-dev-guidance).

Expand the warning details and check the exact wording. If Android offers an explicit
option to proceed, review it and use it only for an APK whose source you trust. Some
blocks offer no such option. Granting “Allow from this source” or “Allow restricted
settings” does not resolve every Play Protect block.

### Temporarily pause scanning to install

If there is no option to proceed, but your device allows pausing protection or turning off
scanning, you can use that option for an APK from the
[official Message487 release](https://github.com/andre487/AndroidMessage487/releases/latest).

1. Open **Google Play → profile icon → Play Protect → ⚙️ Settings**.
2. Turn off **Scan apps with Play Protect** and confirm. If the system offers a timed pause,
   choose a short period sufficient for installation. Names and available options vary by device.
3. Open the downloaded APK again and install Message487.
4. Immediately after the installation attempt, return to Play Protect and turn scanning back on,
   even if installation failed. For a timed pause, also check that protection has resumed instead
   of relying only on the timer.

Google documents the switch in [Play Protect help](https://support.google.com/googleplay/answer/2812853?hl=en).
While disabled, scanning protection is reduced for the entire device, not just Message487.
This does not change the APK's classification: warnings or blocks can return after scanning
resumes, and the app may be disabled or removed. If the switch is unavailable or installation
is still blocked, this method will not resolve it; device-administrator restrictions remain.
Notification access after installation must be configured separately as described below.

If installation stays blocked, report the release version, phone model, Android version
and exact warning in [Issues](https://github.com/andre487/AndroidMessage487/issues), with
personal information removed. The developer can investigate and, where appropriate,
[appeal the classification](https://developers.google.com/android/play-protect/warning-dev-guidance#appeals).
Installation through ADB is another supported Android installation method for your own
device; it is not a guarantee that device policy or security checks will permit the APK.

## Installed app: allow restricted settings

On Android 13 and later, sensitive settings for an app installed from an APK may be
restricted. Only enable this access if you trust the app and intend to forward the data.

1. Open Android **Settings → Apps → See all apps → Message487**.
2. Open the **⋮** menu and select **Allow restricted settings**. Complete the system confirmation.
3. Return to Message487 **Sources**, enable notification forwarding and open the system
   notification-access screen. Select Message487 and allow access, then select source apps
   in Message487.

These steps follow [Google's restricted-settings guide](https://support.google.com/android/answer/12623953?hl=en).
If the menu is absent, first check whether notification access is actually blocked and
whether you are on Message487's app-info page. Availability and names depend on the device;
consult the manufacturer's instructions or the administrator if it remains unavailable.

**Notification access** lets Message487 receive other apps' notifications. The ordinary
**Notifications / Allow notifications** switch only controls an app's own notifications.
Message487 does not need an Accessibility service or device-administrator access.

Enable **SMS** separately in Message487 **Sources** and grant SMS permission when prompted.
There is no need to make Message487 the default SMS app. Android can still redact sensitive
notification content; allowing restricted settings does not guarantee access to one-time codes.
See [Privacy](../../PRIVACY.md) for what is stored and forwarded.

## Install from a computer with ADB

1. Download the release APK to your own computer and install Google's
   [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools).
2. Enable **Developer options → USB debugging** on your phone, connect it by USB,
   unlock it and approve this computer's debugging authorization.
3. In the Platform Tools directory, run the following, replacing the APK path:

```sh
adb devices
adb install -r /path/to/message487-VERSION.apk
```

Use `./adb` if the tool is not on your PATH, or `adb.exe` on Windows. If multiple devices
are listed, add `-s SERIAL` before `install`. The `-r` option updates an existing installation
while keeping its data, provided the package/signature and version are compatible.
Open the app and grant forwarding permissions through its interface. Afterwards, turn off
USB debugging and revoke debugging authorizations if no longer needed.
These steps use [Android's documented ADB installation flow](https://developer.android.com/tools/adb#move).

## Update and check delivery

Install a newer signed release APK over the existing app. If Android reports a signature
conflict, check whether you previously installed a debug build or a different APK.
Do not uninstall as the first fix: uninstalling deletes settings, keys and queued events.

Continue with [n8n setup](n8n-webhook.md), send a test and verify **Journal** and n8n
**Executions**. Then test a new notification or SMS. Granting permissions does not replay
old messages. Release 0.0.1 predates Bearer authentication; follow the release notes and
use a release that includes authentication for a protected webhook.
