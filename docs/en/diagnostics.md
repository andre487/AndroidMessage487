# Diagnostics

[English](../en/diagnostics.md) | [Русский](../ru/diagnostics.md)

The top-bar bug icon opens diagnostics. Refresh reads a bounded log preview; Prepare email creates
an immutable ZIP attachment using FileProvider and temporary read-only access. Email apps are
preferred, with the Android share sheet as fallback. No message is sent by Message487 itself.
Clear logs removes diagnostic segments, the last crash and cached reports; forwarding data is separate.

Private `files/logs` holds three 256 KiB segments and `crash-latest.log` (up to 256 KiB). The writer
uses a single background thread, a 256-entry bounded queue and a lock shared with rotation/export.
Overload drops new entries and records a dropped count on the next successful write. I/O failures
are contained and reported in the diagnostic screen. The preview reads the last 48 KiB plus the
last crash. Private `cache/feedback` keeps at most three uniquely named ZIPs.

The default uncaught-exception handler writes and fsyncs the crash synchronously, persists a prompt
marker and delegates to the previous Android handler. The separate crash file survives normal log
rotation. This covers unhandled Java/Kotlin exceptions, not ANRs, native crashes, force stops or
process termination without an exception. Storage exhaustion or severe process failure can prevent
saving a report. Exception messages and thread names are excluded; causes, suppressed exceptions
and frames are bounded. App symbol names and source lines are preserved for release diagnostics.

Logs use a fixed event vocabulary and typed/allowlisted metadata. Never add message bodies, sender
addresses, package names, URLs, event/device IDs, device codes or arbitrary exception messages.
The archive adds app/Android/device versions, architecture and nonsensitive configuration flags.

Run `bundle exec fastlane android checks`. Diagnostic tests cover rotation/restart, crash retention,
redaction, storage failure, handler delegation, archive retention and attachment permissions.
For a manual debug-emulator check, start the app, then run
`adb shell am crash life.andre.message487` and reopen it. Review the next-launch prompt and ZIP in
the diagnostics screen. Stop at the email composer unless intentionally sending a real report.
