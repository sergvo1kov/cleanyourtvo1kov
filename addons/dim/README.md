# Dim (fork-only)

Air-raid alerts for Odeskyi raion, the weather in Odesa, an internet check with a speed test, and Smart Life
(Tuya) devices, on one screen. It also publishes one status row (`content://ua.volkov.dim.status/now`) that this
fork's launcher shows under the clock (`launcher/.../HomeStatus.java`).

- Alerts: alerts.in.ua with a token (raion level), otherwise ubilling.net.ua (oblast level).
- Weather: Open-Meteo, no key. Speed test: speed.cloudflare.com. Internet check: gstatic generate_204.
- Smart Life: Tuya cloud, Central Europe, read only. Access ID and Secret are typed on the TV (Settings) and stay
  in the app's private storage; never commit them.
- Nothing runs in the background: data refreshes only while Home or this screen reads it.
