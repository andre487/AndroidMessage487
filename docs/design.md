# Interface design

Message487 uses a restrained Material 3 interface with the standard purple baseline palette from
Material 3. Both light and dark schemes come directly from the library without color overrides
or wallpaper-derived dynamic colors. The visual reference is MegaProxy: prominent operational status,
grouped settings, rounded surfaces, readable typography and a bounded content width.

The overview answers whether capture is ready and what needs attention. Its status does not claim
that the server is reachable: a test event and the journal provide delivery evidence. Primary
screens outside the overview have an app-bar back button returning to the overview. Primary
sections use bottom navigation on compact windows and a rail on wider windows. Connection drafts
survive section changes. Long application and event lists use lazy rendering.

The journal prioritizes source, time and delivery state. Tap an event for its selectable ID,
HTTP response, attempts, retry and deletion. Message text remains hidden. Source selection supports
search, a selected-only filter and bulk selection of the full available list. Manual package
entry is a separate dialog. Help retains the
explanations of queue behavior, retries and system limitations.

Keep touch targets at least 48 dp. Communicate status with text and icons as well as color.
Use the theme's semantic color roles, scalable typography, safe insets and scrolling; avoid fixed
text heights. Verify both locales, dark mode, large text and narrow/wide windows on an emulator.

## References

- [Google: Themes](https://developer.android.com/design/ui/mobile/guides/styles/themes) — the standard Material purple baseline.
- [Google: Layout basics](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-basics) — grouping, consistent spacing, safe areas and reachable actions.
- [Google: Accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility) — contrast, scalable text, touch targets and semantics.
- [Material 3: Navigation bar](https://m3.material.io/components/navigation-bar/guidelines) — primary destinations.
- [Google Design: Expressive design research](https://design.google/library/expressive-material-design-google-research) — color, scale and containment should emphasize useful actions while preserving familiar behavior.
- [Nielsen Norman Group: Visual hierarchy](https://www.nngroup.com/articles/visual-hierarchy-ux-definition/) — emphasize important information through scale, contrast and grouping.

## Verification

The redesign was checked on the API 35 emulator in English and Russian, light and dark mode,
with enlarged text on a narrow window, and with rail navigation in a wide window. Android Lint,
JVM tests and both APK builds use the existing Fastlane checks lane.
