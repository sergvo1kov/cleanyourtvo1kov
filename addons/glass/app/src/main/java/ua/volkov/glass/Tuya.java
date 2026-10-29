package ua.volkov.glass;

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
            row.put("id", devId);
            byId.put(devId, row);
        }
        try {
            JSONObject st = call(id, secret, "/v1.0/iot-03/devices/status?device_ids=" + ids);
            JSONArray sa = st.optJSONArray("result");
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    JSONObject s = sa.getJSONObject(i);
                    JSONObject row = byId.get(s.optString("id"));
                    if (row == null) continue;
                    JSONArray codes = s.optJSONArray("status");
                    row.put("summary", summary(codes));
                    String sw = switchCode(codes);
                    if (sw != null) {
                        row.put("switch_code", sw);
                        row.put("switch_on", switchValue(codes, sw));
                    }
                }
            }
        } catch (IOException e) {
            // names without values are still useful
        }
        for (JSONObject row : byId.values()) out.put(row);
        return out;
    }

    private static final String[] SWITCHES = {"switch_led", "switch_1", "switch", "switch_led_1", "power", "switch_2"};

    /** The on/off data point OK toggles, or null when the device has none (a sensor). */
    static String switchCode(JSONArray status) {
        if (status == null) return null;
        for (String want : SWITCHES) {
            for (int i = 0; i < status.length(); i++) {
                JSONObject o = status.optJSONObject(i);
                if (o != null && want.equals(o.optString("code")) && o.opt("value") instanceof Boolean) return want;
            }
        }
        return null;
    }

    private static boolean switchValue(JSONArray status, String code) {
        for (int i = 0; i < status.length(); i++) {
            JSONObject o = status.optJSONObject(i);
            if (o != null && code.equals(o.optString("code"))) return o.optBoolean("value");
        }
        return false;
    }

    /** Sends one data point to a device, e.g. switch_1 = true. */
    static synchronized void command(String id, String secret, String devId, String code, Object value)
            throws IOException, JSONException {
        ensureToken(id, secret);
        JSONObject cmd = new JSONObject().put("code", code).put("value", value);
        String body = new JSONObject().put("commands", new JSONArray().put(cmd)).toString();
        String path = "/v1.0/iot-03/devices/" + devId + "/commands";
        JSONObject r = post(id, secret, path, body);
        if (!r.optBoolean("success", false) && r.optInt("code") == 1010) {
            tokenUntil = 0;
            ensureToken(id, secret);
            r = post(id, secret, path, body);
        }
        if (!r.optBoolean("success", false)) {
            throw new IOException("Tuya: " + r.optString("msg", "ошибка") + " (" + r.optInt("code") + ")");
        }
    }

    private static JSONObject post(String id, String secret, String path, String body) throws IOException, JSONException {
        String t = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String stringToSign = "POST\n" + sha256(body) + "\n\n" + path;
        String sign = hmac(secret, id + token + t + nonce + stringToSign);
        Map<String, String> h = new HashMap<>();
        h.put("client_id", id);
        h.put("sign", sign);
        h.put("t", t);
        h.put("nonce", nonce);
        h.put("sign_method", "HMAC-SHA256");
        h.put("access_token", token);
        return new JSONObject(Http.post(BASE + path, h, body, 15_000).body);
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
        // on/off is shown on its own line with the OK hint (DimActivity), not in the summary
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
