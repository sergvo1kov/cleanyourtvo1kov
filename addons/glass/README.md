# Flick TV Glass

A second home screen in the style of tvOS, by Serhii Volkov ([@sergvo1kov](https://github.com/sergvo1kov)), that
stands beside [Flick TV](../dim/README.md) instead of replacing it. Package `ua.volkov.glass`, Android 8+.

- **The stage:** the focused film fills the screen behind its title, year, genres, TMDB rating and synopsis, with
  carousel dots; it moves on by itself every nine seconds. Up from the dock picks a film with ◀ ▶, OK opens it in
  Lampa. The films are Flick TV's row: Lampa's Continue watching and recommendations, the Must Want list, Must
  picks and fresh TMDB titles, each with its badge.
- **The dock:** six favourite apps in a frosted band and a settings tile. Down opens all apps; hold OK to add,
  move or remove a dock app.
- **Capsules:** weather on the left (OK: hourly and five-day forecast from Open-Meteo), the clock with the alert
  state on the right (OK: the Control Center).
- **Control Center:** StandBy (the screensaver), network, Bluetooth, inputs, settings, the alert state (OK fetches
  it again), sleep, TorrServe, Smart Life lights as round toggles, and the switch back to Flick TV.
- **Settings:** tvOS split-pane: which home screen Home opens, the auto-advance, the dock, the TV's own settings.

## How it works with Flick TV

Glass keeps no keys. Flick TV (2.8+) answers its `DataProvider` (`content://ua.volkov.dim.data`), guarded by a
signature permission, so only apps signed with the fork's key can read it. Flick TV's Home-button service opens
whichever home screen was chosen last: Glass when it is in front, Flick TV after "Главный экран" in Glass's
settings or the switch in its Control Center. Keys and services are entered in Flick TV's Control Center.

If Glass ever crashes, it hands Home back to Flick TV first.

## Install

Install Flick TV 2.8 or newer first, then the Glass APK from its release (`glass-v1.0`); both are signed with the
same key. Open Glass once from the apps list: Home now opens it.

Licence: MIT, as the rest of this repository.
