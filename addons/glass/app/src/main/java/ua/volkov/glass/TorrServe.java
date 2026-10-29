package ua.volkov.glass;

import java.net.HttpURLConnection;
import java.net.URL;

/** Is the TorrServe server on this TV answering? It listens on port 8090 by default. */
final class TorrServe {
    private TorrServe() {}

    /** The version string, or null when it does not answer. Blocking. */
    static String version() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:8090/echo").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try {
                if (c.getResponseCode() != 200) return null;
                String v = Http.read(c.getInputStream()).trim();
                return v.isEmpty() ? "?" : v;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
