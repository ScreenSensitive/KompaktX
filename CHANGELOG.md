# Changelog

## v1.1 — 2026-04-29

First update since launch. Focuses on theming, recents customization, keyboard
behavior, and visual polish.

### New

- **System theme support.** New "Theme" picker with **System / Light / Dark**.
  Pure black-and-white only — no greys, no accent tints. The entire settings UI
  and the notification panel invert cleanly in dark mode. System status-bar and
  navigation-bar icon tints follow the active theme automatically.

- **Recents layout: Inline or Tabbed.** New "Recents Layout" picker under
  Features. **Inline** keeps the original behavior — a horizontal recents row
  pinned to the bottom of the panel. **Tabbed** turns Recents into its own pill
  tab to the left of Notifications and Settings, showing a full vertical list of
  recent apps with the last-opened time displayed on the right of each row.

- **Hide App Icons.** New toggle that hides app icons across the panel — recents,
  notification entries, app group headers, and the heads-up popup. In the inline
  recents row the icon is replaced with a clean first-letter badge so the layout
  stays consistent. In the tabbed Recents list the rows go fully text-only.

- **Tab Position.** New picker to place the tab bar at the **Top** or **Bottom**
  of the panel.

### Changed

- **Permissions reorganized.** "Modify System Settings" moved out of the Custom
  Tones section and into the main Permissions card at the top, alongside the
  others. Description now reflects that it's needed for both brightness control
  and custom tones.

- **Clear All redesign.** Replaced the misaligned bordered pill with a clean
  inline header at the top of the notification list — left side shows the
  notification count, right side is a plain "Clear all" text link, padded to
  match the notification entries below.

- **Slimmer edge swipe strips.** All four edge swipe triggers are now narrow
  enough to live on the very edge of the screen and stay out of the way of the
  keyboard and other on-screen content. Bottom strips went from 40×100 dp to
  80×16 dp (wider for easier targeting, much shorter so they don't reach into
  the keyboard area); side strips went from 20×100 dp to 16×80 dp.

- **Recents tab list cleanup.** No dividers between rows. Last-opened time is
  shown on the right side of the label using the same relative format as
  notification timestamps ("Just now", "12m ago", "Mar 4").

### Icon

- **Refreshed launcher icon.** Pure-black "KX" mark on a white background,
  rebuilt from scratch as a proper adaptive icon. The legacy hardcoded frame is
  removed — the system mask (circle / squircle / rounded square) applies
  cleanly on every launcher. Legacy mipmap PNGs regenerated at all densities;
  round and square variants both follow the same mark.

- **App-store assets exported.** 1024×1024 and 512×512 store-ready icons live
  in `app_store/` for use on listings.

### Build

- Version bump to **1.1** (`versionCode 2`).
