**Flick TV 2.6** — a home screen for Android TV / Google TV by Serhii Volkov (@sergvo1kov), built on CleanYourTV.

**New in 2.6**
- Settings are split: the settings sheet's «Flick TV» holds the look (theme, film backdrop on/off, Google TV panel, TV settings); every key and service (Must, TMDB, alerts.in.ua, Tuya, Telegram) is entered in the Control Center's Settings.
- OK on the big alert card fetches the alerts again at once.

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

Install: download `Flick-TV-2.6.zip`, unzip on a Windows PC with Android platform-tools, run `УСТАНОВИТЬ.bat` (details in `ИНСТРУКЦИЯ.txt`).
Tested on an Ergo 55MQ95 Gaming (Google TV, MediaTek). Package `ua.volkov.dim`, Android 8+.
