# Flick AirPlay

An AirPlay receiver for the TV, standalone: it runs as its own app with a background service, starts with the
TV, and keeps working with Flick TV and Flick TV Glass removed. The launchers only show its state and open it.

Based on [PhairPlay](https://github.com/mazer666/PhairPlay) (Apache License 2.0, see `LICENSE`; the upstream
README and changelog are kept as `UPSTREAM-README.md` and `UPSTREAM-CHANGELOG.md`). Changes here: package
`ua.volkov.airplay`, the name "Flick AirPlay", ARM-only native libraries, and the fork's signing key in CI.

What it does (from upstream): screen mirroring from iPhone, iPad and Mac, AirPlay audio (ALAC, AAC), track
name and cover art, the TV remote controlling the phone's playback, photos. Local network only; no ads, no
analytics. Apple's protected content (Netflix, Apple TV+, Apple Music DRM tracks) cannot be received by any
open-source receiver.

Build: `./gradlew assembleGoogletvRelease` (JDK 17, Android SDK 35, NDK 28.2.13676358, CMake 3.22.1).
Published by `.github/workflows/build-airplay.yml` as the `airplay-v<version>` release.
