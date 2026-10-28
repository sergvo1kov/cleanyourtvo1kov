package ua.volkov.dim;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latest posts of public Telegram channels, read from their public web preview (t.me/s/<channel>).
 * No account, no API key, read only.
 */
final class Telegram {
    static final String DEFAULT_CHANNELS = "war_monitor";
    /** Only posts about an immediate threat; everything else is dropped. Ukrainian and Russian stems. */
    private static final Pattern URGENT = Pattern.compile("(ракет|баліст|балист|шахед|бпла|мопед|(^|[^а-яёіїєґ])каб(и|ы|ів|ов)?([^а-яёіїєґ]|$)|крилат|крылат|"
            + "вибух|взрыв|загроз|угроз|швидкісн|скоростн|пуск|зліт|взлет|взлёт|міг-31|миг-31|ту-95|ту-22|калібр|калибр|"
            + "кинджал|кинжал|циркон|іскандер|искандер|укритт|укрыт|🚨|‼|❗)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ODESA = Pattern.compile("(одес|одещ|одеськ|одесск)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    static final long MAX_AGE_MS = 6 * 3600_000L;
    static final int KEEP = 40;

    private static final Pattern TITLE = Pattern.compile(
            "tgme_channel_info_header_title\"[^>]*>\\s*<span[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern POST = Pattern.compile("data-post=\"([^\"]+)\"");
    private static final Pattern TEXT = Pattern.compile(
            "class=\"tgme_widget_message_text js-message_text[^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile("<time[^>]*datetime=\"([^\"]+)\"");

    private Telegram() {}

    static List<String> channels(String setting) {
        List<String> out = new ArrayList<>();
        for (String s : (setting.isEmpty() ? DEFAULT_CHANNELS : setting).split("[,\\s]+")) {
            String c = s.trim().replace("https://t.me/s/", "").replace("https://t.me/", "").replace("t.me/", "")
                    .replace("@", "");
            if (c.matches("[A-Za-z0-9_]{4,64}")) out.add(c);
        }
        return out;
    }

    /** Posts of all channels, newest first: [{channel, title, text, ms, post}]. */
    static JSONArray feed(List<String> channels) throws JSONException {
        List<JSONObject> all = new ArrayList<>();
        IOException last = null;
        for (String ch : channels) {
            try {
                all.addAll(channel(ch));
            } catch (IOException e) {
                last = e;
            }
        }
        if (all.isEmpty() && last != null) throw new JSONException(String.valueOf(last.getMessage()));
        all.sort((a, b) -> Long.compare(b.optLong("ms"), a.optLong("ms")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size() && i < KEEP; i++) out.put(all.get(i));
        return out;
    }

    static List<JSONObject> channel(String ch) throws IOException, JSONException {
        Http.Response r = Http.get("https://t.me/s/" + ch, null, 15_000);
        if (r.code != 200) throw new IOException("t.me/" + ch + ": HTTP " + r.code);
        String html = r.body;
        Matcher t = TITLE.matcher(html);
        String title = t.find() ? clean(t.group(1)) : ch;
        List<JSONObject> out = new ArrayList<>();
        // One block per message: from one data-post to the next.
        Matcher m = POST.matcher(html);
        List<int[]> starts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[]{m.start()});
            ids.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : html.length();
            String block = html.substring(from, to);
            Matcher tx = TEXT.matcher(block);
            if (!tx.find()) continue;   // photo or video without words
            String text = clean(tx.group(1));
            if (text.isEmpty()) continue;
            Matcher tm = TIME.matcher(block);
            long ms = 0;
            if (tm.find()) {
                try {
                    ms = OffsetDateTime.parse(tm.group(1)).toInstant().toEpochMilli();
                } catch (RuntimeException ignored) {
                    // keep 0
                }
            }
            if (!URGENT.matcher(text).find()) continue;
            if (ms > 0 && System.currentTimeMillis() - ms > MAX_AGE_MS) continue;
            JSONObject o = new JSONObject();
            o.put("odesa", ODESA.matcher(text).find());
            o.put("channel", ch);
            o.put("title", title);
            o.put("text", text);
            o.put("ms", ms);
            o.put("post", ids.get(i));
            out.add(o);
        }
        return out;
    }

    /** HTML of a post → plain text with line breaks. */
    static String clean(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        Matcher n = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (n.find()) {
            String rep;
            try {
                int cp = Integer.parseInt(n.group(2), n.group(1).isEmpty() ? 10 : 16);
                rep = new String(Character.toChars(cp));
            } catch (RuntimeException e) {
                rep = "";
            }
            n.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        n.appendTail(b);
        return b.toString().replace("&amp;", "&").replaceAll("\n{3,}", "\n\n").trim();
    }
}
