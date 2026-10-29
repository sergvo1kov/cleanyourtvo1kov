package ua.volkov.glass;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Iterator;
import java.util.TreeSet;

/**
 * Air-raid alerts for Odesa (Odeskyi raion) and the count of oblasts under alert.
 * With an alerts.in.ua token: raion-level data. Without one: oblast-level data from ubilling.net.ua.
 */
final class Alerts {
    static final String OBLAST = "Одеська область";
    static final String RAION = "Одеський район";

    static final int UNKNOWN = -1, CALM = 0, ALERT = 1, PARTIAL = 2;

    static final class Result {
        int state = UNKNOWN;
        long sinceMs;
        String scope = RAION;
        String source = "";
        TreeSet<String> oblasts = new TreeSet<>();
    }

    private Alerts() {}

    static Result fetch(String token) throws IOException, JSONException {
        return token.isEmpty() ? fetchUbilling() : fetchAlertsInUa(token);
    }

    private static Result fetchAlertsInUa(String token) throws IOException, JSONException {
        Http.Response r = Http.get("https://api.alerts.in.ua/v1/alerts/active.json",
                Collections.singletonMap("Authorization", "Bearer " + token), 15_000);
        if (r.code == 401 || r.code == 403) throw new IOException("alerts.in.ua: токен не принят (" + r.code + ")");
        if (r.code == 429) throw new IOException("alerts.in.ua: слишком много запросов");
        if (r.code != 200) throw new IOException("alerts.in.ua: HTTP " + r.code);
        return parseAlertsInUa(new JSONObject(r.body));
    }

    static Result parseAlertsInUa(JSONObject root) {
        Result res = new Result();
        res.source = "alerts.in.ua";
        res.scope = RAION;
        res.state = CALM;
        JSONArray arr = root.optJSONArray("alerts");
        if (arr == null) return res;
        long since = Long.MAX_VALUE;
        boolean partial = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null || !"air_raid".equals(a.optString("alert_type"))) continue;
            if (!a.isNull("finished_at") && !a.optString("finished_at").isEmpty()) continue;
            String oblast = a.optString("location_oblast");
            String type = a.optString("location_type");
            String title = a.optString("location_title");
            String raion = a.optString("location_raion");
            if (!oblast.isEmpty()) res.oblasts.add(shortOblast(oblast));
            if (!OBLAST.equals(oblast)) continue;
            boolean ours = "oblast".equals(type)
                    || RAION.equals(title)
                    || (title.contains("Одес") && !"raion".equals(type));   // Odesa city / Odesa hromada
            if (ours) {
                long t = parseIso(a.optString("started_at"));
                if (t > 0 && t < since) since = t;
                res.state = ALERT;
            } else if (RAION.equals(raion)) {
                partial = true;
            }
        }
        if (res.state != ALERT && partial) res.state = PARTIAL;
        res.sinceMs = since == Long.MAX_VALUE ? 0 : since;
        return res;
    }

    private static Result fetchUbilling() throws IOException, JSONException {
        Http.Response r = Http.get("https://ubilling.net.ua/aerialalerts/", null, 15_000);
        if (r.code != 200) throw new IOException("ubilling: HTTP " + r.code);
        Result res = new Result();
        res.source = "ubilling.net.ua";
        res.scope = OBLAST;
        JSONObject states = new JSONObject(r.body).optJSONObject("states");
        if (states == null) throw new IOException("ubilling: неизвестный формат");
        res.state = CALM;
        for (Iterator<String> it = states.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject st = states.optJSONObject(name);
            if (st == null || !st.optBoolean("alertnow", false)) continue;
            res.oblasts.add(shortOblast(name));
            if (name.contains("Одеськ")) {
                res.state = ALERT;
                res.sinceMs = parseLocal(st.optString("changed"));
            }
        }
        return res;
    }

    /** "Одеська область" → "Одеська"; "м. Київ" stays. */
    static String shortOblast(String name) {
        return name.replace(" область", "").trim();
    }

    static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long parseLocal(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.of("Europe/Kyiv")).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static String join(TreeSet<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            if (b.length() > 0) b.append(", ");
            b.append(n);
        }
        return b.toString();
    }

    /** "7 областях" with the right Russian ending. */
    static String oblastsWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return n + " области";
        return n + " областях";
    }

    /** The API's Ukrainian scope names, as shown on screen. */
    static String label(String scope) {
        if (RAION.equals(scope)) return "Одесский район";
        if (OBLAST.equals(scope)) return "Одесская область";
        return scope;
    }
}
