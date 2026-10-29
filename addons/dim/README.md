# Flick TV 2.1

A home screen for Android TV / Google TV, made by **Serhii Volkov ([@sergvo1kov](https://github.com/sergvo1kov))**
on top of [CleanYourTV](https://github.com/Gathin23/cleanyourtv). Russian UI. Package `ua.volkov.dim`.

Tested on an Ergo 55MQ95 Gaming (Google TV, MediaTek).

## What is on the screen

- **For you:** recommendations "like what you watched" from a public Must profile, with posters. One press opens
  the film's card in Lampa (with a free TMDB key in Settings → Flick TV; without one, Lampa's search opens).
  The headline above the posters changes every 5–10 minutes ("Сегодня на экране", "Успей посмотреть"…).
- **Now playing** for YouTube Music / Apple Music and other media sessions, with controls.
- **Air-raid alerts** for Odeskyi raion (alerts.in.ua with a token, otherwise ubilling.net.ua at oblast level), and
  urgent posts from one Telegram channel.
- **Weather** (Open-Meteo), **internet check and speed test** (Cloudflare), **TorrServe status**.
- **Smart Life / Tuya** devices: switches toggle from the bottom row; sensors show their values.
- Favourites row and a bottom row of up to six tiles, both editable with a long press.
- Remote battery (GATT) and sound output chips; ambient background; dark and light themes.
- **Settings sheet** (Настройки): network, Bluetooth, picture mode, standby, volume, screensaver start and delay,
  back to the stock Google TV home, all TV settings.
- **Control Center** (Центр управления): alerts, weather, internet, TorrServe, Telegram, Smart Life on one screen.

## Setup on the TV (over ADB)

```
adb install dim.apk
adb shell cmd package set-home-activity --user 0 ua.volkov.dim/.HomeActivity
adb shell settings put secure enabled_accessibility_services <existing>:ua.volkov.dim/.HomeRedirectService
adb shell cmd notification allow_listener ua.volkov.dim/ua.volkov.dim.NowPlayingListener
adb shell pm grant ua.volkov.dim com.android.providers.tv.permission.READ_EPG_DATA
adb shell appops set ua.volkov.dim WRITE_SETTINGS allow     # screensaver delay from the settings sheet
```

The accessibility service only watches the Google TV launcher's window changes: it sends its home screen to Flick
TV (the Google Home side panel on a long Home press stays), and puts the TV to standby from the settings sheet.

Keys and tokens (Tuya, alerts.in.ua, TMDB) are typed on the TV and stay in the app's private storage. Never commit
them.

## Build

CI (`.github/workflows/build-apk.yml`) builds `dim.apk` and publishes it on the `latest-build` release. The
signing key in `launcher/ci-debug.keystore` is a fixed debug key so updates install over each other.

Licence: MIT, as the rest of this repository.
