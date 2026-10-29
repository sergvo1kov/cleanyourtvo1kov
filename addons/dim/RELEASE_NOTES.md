**Flick TV 3.4** — a home screen for Android TV / Google TV by Serhii Volkov (@sergvo1kov), built on CleanYourTV.

**New in 3.4: updates over the air**
- Settings → Flick TV → «Обновления» checks this repository's releases for Flick TV and AirPlay, downloads the newer APK over HTTPS and hands it to Android's installer (one confirmation on screen; an APK signed with another key is refused).
- Home checks quietly at most every 12 hours and shows a note when something is newer. No computer needed any more.

**New in 3.3**
- Flick TV is the one home screen again: the second home screen and everything that served it (its data provider, the Home switch) are gone.

**New in 3.2**
- Now Playing has a small equalizer that moves while music plays and settles on pause; AirPlay is named just "AirPlay", and its button reads "Открыть".
- The focused play button is no longer cut off.

**New in 3.1**
- Now Playing takes the cover's colours (card, progress line, play button) and has drawn, centred buttons.
- Now Playing is read off the main thread, so the screen no longer stalls while music plays.

**New in 3.0: AirPlay**
- An AirPlay button in the bottom row (added once, next to the lights) opens AirPlay, the separate receiver app; it shows the track while the phone plays.
- What plays over AirPlay shows as Now Playing, like any music app.

**New in 2.7: faster**
- The film row is parsed once and kept, instead of being rebuilt from storage every second by the clock.
- The clock, date and alert pill only touch the screen when their text changes, so Home no longer lays itself out every second.
- Must's six "similar" lists download at the same time; Lampa's cards are read every 2 minutes with only the columns used.
- The six favourites fill the glass band, with equal gaps on the left and right.

**New in 2.6**
- Settings are split: the settings sheet's «Flick TV» holds the look (theme, film backdrop on/off, Google TV panel, TV settings); every key and service (Must, TMDB, alerts.in.ua, Tuya, Telegram) is entered in the Control Center's Settings.
- OK on the big alert card fetches the alerts again at once.
- The headline over the films changes every 7–10 minutes on its own clock (a Home press no longer resets it), and the row refreshes itself: Must every 30 minutes, fresh titles every 3 hours, a new order every 40 minutes.
- A focused tile in the settings sheet is no longer cut at the sheet's edge; the backdrop changes through a smooth blur.

**For you**
- One row from four sources, each with its own badge: Lampa's Continue watching («Продолжить»), your Must Want list («Хочу посмотреть»), Must picks like what you watched («Похожее»), fresh TMDB films and shows («Новинка»), and Lampa's own recommendations («От Lampa»). The hint says why a film is there.
- Eight larger posters across the page; the next ones are dimmed until you scroll to them.
- A Lampa-style backdrop behind the row: the focused film's picture fills the screen and changes through a soft blur.
- One press opens the film's card in Lampa. Fresh titles and the backdrop need a free TMDB key (Настройки → Flick TV → Lampa).
- Posters load from a disk cache after the first time, four downloads at once, in the size the row needs.

**Also**
- Lampa's cards are read with READ_TV_LISTINGS (Android 11+), and Lampa is asked to build its channels (INITIALIZE_PROGRAMS), as the stock launcher does.
- The Control Center's Settings shows the same Flick TV list as Home.
- Settings sheet, three-row apps page, alerts, weather, Smart Life, TorrServe and Now playing from 2.0.
- `ВЕРНУТЬ-GOOGLE-TV.bat` in the installer zip takes a TV back to the stock Google TV home and removes Flick TV.

Install: download `Flick-TV-2.8.zip`, unzip on a Windows PC with Android platform-tools, run `УСТАНОВИТЬ.bat` (details in `ИНСТРУКЦИЯ.txt`).
Tested on an Ergo 55MQ95 Gaming (Google TV, MediaTek). Package `ua.volkov.dim`, Android 8+.
