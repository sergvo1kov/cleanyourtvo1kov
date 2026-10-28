package ua.volkov.dim;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Smart Life devices through the Tuya cloud (Central Europe data centre), read only:
 * the device list of the linked app account and each device's status.
 */
final class Tuya {
    private static final String BASE = "https://openapi.tuyaeu.com";
    private static String token = "";
    private static long tokenUntil;

    private Tuya() {}

    /** [{name, online, summary}] for every device in the project. */
    static synchronized JSONArray devices(String id, String secret) throws IOException, JSONException {
        ensureToken(id, secret);
        JSONObject list = call(id, secret, "/v2.0/cloud/thing/device?page_size=20");
        JSONArray arr = list.optJSONArray("result");
        JSONArray out = new JSONArray();
        if (arr == null || arr.length() == 0) return out;
        StringBuilder ids = new StringBuilder();
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.getJSONObject(i);
            String devId = d.optString("id");
            if (devId.isEmpty()) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(devId);
            JSONObject row = new JSONObject();
            String name = d.optString("customName");
            if (name.isEmpty()) name = d.optString("name");
            row.put("name", name.isEmpty() ? devId : name);
            row.put("online", d.optBoolean("isOnline", d.optBoolean("online", true)));
            row.put("category", d.optString("category"));
            row.put("summary", "");
            byId.put(devId, row);
        }
        try {
            JSONObject st = call(id, secret, "/v1.0/iot-03/devices/status?device_ids=" + ids);
            JSONArray sa = st.optJSONArray("result");
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    JSONObject s = sa.getJSONObject(i);
                    JSONObject row = byId.get(s.optString("id"));
                    if (row != null) row.put("summary", summary(s.optJSONArray("status")));
                }
            }
        } catch (IOException e) {
            // names without values are still useful
        }
        for (JSONObject row : byId.values()) out.put(row);
        return out;
    }

    /** The values worth a glance, in plain words: "22.5° · 45% · батарея 80%". */
    static String summary(JSONArray status) {
        if (status == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < status.length(); i++) {
            JSONObject s = status.optJSONObject(i);
            if (s == null) continue;
            String code = s.optString("code");
            Object v = s.opt("value");
            String part = describe(code, v);
            if (part == null) continue;
            if (b.length() > 0) b.append("  ·  ");
            b.append(part);
            if (b.length() > 60) break;
        }
        return b.toString();
    }

    private static String describe(String code, Object v) {
        if (v == null) return null;
        String c = code.toLowerCase(Locale.ROOT);
        if (c.contains("temp") && !c.contains("unit") && !c.contains("set") && v instanceof Number) {
            double t = ((Number) v).doubleValue();
            if (Math.abs(t) >= 100) t /= 10.0;
            return String.format(Locale.ROOT, "%.1f°", t);
        }
        if ((c.contains("humidity") || c.equals("va_humidity")) && !c.contains("set") && v instanceof Number) {
            double h = ((Number) v).doubleValue();
            if (h > 100) h /= 10.0;
            return Math.round(h) + "%";
        }
        if (c.contains("battery_percentage") || c.equals("battery_value") || c.equals("residual_electricity")) {
            return "батарея " + v + "%";
        }
        if (c.equals("doorcontact_state")) return Boolean.TRUE.equals(v) ? "открыто" : "закрыто";
        if (c.equals("pir")) return "pir".equals(String.valueOf(v)) ? "движение" : "тихо";
        if (c.equals("watersensor_state")) return "alarm".equals(String.valueOf(v)) ? "ПРОТЕЧКА" : "сухо";
        if (c.equals("smoke_sensor_status")) return "alarm".equals(String.valueOf(v)) ? "ДЫМ" : "ок";
        if ((c.equals("switch") || c.equals("switch_1") || c.equals("switch_led") || c.equals("switch_led_1"))
                && v instanceof Boolean) {
            return (Boolean) v ? "включено" : "выключено";
        }
        if (c.equals("cur_power") && v instanceof Number) {
            return String.format(Locale.ROOT, "%.0f Вт", ((Number) v).doubleValue() / 10.0);
        }
        return null;
    }

    private static void ensureToken(String id, String secret) throws IOException, JSONException {
        if (!token.isEmpty() && System.currentTimeMillis() < tokenUntil) return;
        token = "";
        JSONObject r = request(id, secret, "", "/v1.0/token?grant_type=1");
        JSONObject res = r.getJSONObject("result");
        token = res.getString("access_token");
        tokenUntil = System.currentTimeMillis() + Math.max(60, res.optLong("expire_time", 3600) - 120) * 1000L;
    }

    private static JSONObject call(String id, String secret, String pathQuery) throws IOException, JSONException {
        JSONObject r = request(id, secret, token, pathQuery);
        if (!r.optBoolean("success", false) && r.optInt("code") == 1010) {   // token invalid: once more
            tokenUntil = 0;
            ensureToken(id, secret);
            r = request(id, secret, token, pathQuery);
        }
        return r;
    }

    private static JSONObject request(String id, String secret, String accessToken, String pathQuery)
            throws IOException, JSONException {
        String t = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String stringToSign = "GET\n" + sha256("") + "\n\n" + pathQuery;
        String sign = hmac(secret, id + accessToken + t + nonce + stringToSign);
        Map<String, String> h = new HashMap<>();
        h.put("client_id", id);
        h.put("sign", sign);
        h.put("t", t);
        h.put("nonce", nonce);
        h.put("sign_method", "HMAC-SHA256");
        if (!accessToken.isEmpty()) h.put("access_token", accessToken);
        Http.Response r = Http.get(BASE + pathQuery, h, 15_000);
        JSONObject o = new JSONObject(r.body);
        if (!o.optBoolean("success", false) && o.optInt("code") != 1010) {
            throw new IOException("Tuya: " + o.optString("msg", "ошибка") + " (" + o.optInt("code") + ")");
        }
        return o;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(d).toLowerCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmac(String secret, String s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.ROOT, "%02X", x));
        return b.toString();
    }
}
