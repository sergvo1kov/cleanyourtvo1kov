package ua.volkov.dim;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Experiment: can this app read the cards Lampa publishes to Android TV (preview channels, Watch Next)?
 * Writes what it finds to lampa-debug.txt in the app's external files folder.
 */
final class LampaCards {
    private LampaCards() {}

    static void probe(Context ctx) {
        StringBuilder b = new StringBuilder();
        for (String table : new String[]{"channel", "preview_program", "watch_next_program"}) {
            Uri uri = Uri.parse("content://android.media.tv/" + table);
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c == null) {
                    b.append(table).append(": null cursor\n");
                    continue;
                }
                b.append(table).append(": ").append(c.getCount()).append(" rows\n");
                int pkg = c.getColumnIndex("package_name");
                int title = c.getColumnIndex(table.equals("channel") ? "display_name" : "title");
                int n = 0;
                while (c.moveToNext() && n++ < 15) {
                    b.append("  ").append(pkg >= 0 ? c.getString(pkg) : "?").append(" | ")
                            .append(title >= 0 ? c.getString(title) : "?").append('\n');
                }
            } catch (Exception e) {
                b.append(table).append(": ").append(e).append('\n');
            }
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            try (FileOutputStream f = new FileOutputStream(new File(dir, "lampa-debug.txt"))) {
                f.write(b.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // debugging aid only
        }
    }
}
