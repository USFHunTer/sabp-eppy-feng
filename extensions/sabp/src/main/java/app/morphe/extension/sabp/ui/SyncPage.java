package app.morphe.extension.sabp.ui;

import android.content.Intent;
import android.net.Uri;
import android.text.format.DateUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.morphe.extension.sabp.EppyBook;
import app.morphe.extension.sabp.EppyPlayer;
import app.morphe.extension.sabp.OtherDevicePosition;

/**
 * Settings → Sync between devices ("Sync between devices" patch). Syncthing pairs the devices and copies the library
 * folder (docs/adr/0002); this page shows what the playing book's folder holds, checks now, and opens Syncthing.
 */
public final class SyncPage extends Page {
    public static final String KEY = "sync";
    /** Syncthing-Fork, then the original (discontinued) app. Listed in the manifest's queries by the patch. */
    public static final String[] SYNCTHING = {"com.github.catfriend1.syncthingandroid", "com.nutomic.syncthingandroid"};

    private TextView status;

    public SyncPage(Host host) {
        super(host, KEY, null, "Sync between devices", true);
        build();
    }

    private void build() {
        sectionTitle("This book");
        LinearLayout book = card();
        status = note(book, "Looking in the book's folder…");
        book.addView(linkRow(EppyIcon.SYNC, "Check the other device", "Look for a newer position now, and jump there if there is one", v -> {
            Ui.haptic(v, t);
            host.checkOtherDeviceNow();
        }));
        refreshStatus();

        sectionTitle("Pairing and copying");
        LinearLayout st = card();
        String found = installedSyncthing(c);
        note(st, found == null ? "Syncthing: not installed on this device."
                : "Syncthing: installed (" + (found.equals(SYNCTHING[0]) ? "Syncthing-Fork" : "the original app, no longer updated") + ").");
        note(st, "Syncthing pairs your phone and tablet and copies the library folder between them on your home Wi-Fi. "
                + "eppy then offers the other device's position when you open a book. Set it up once on both devices:\n"
                + "1. Install Syncthing-Fork.\n"
                + "2. In its settings turn off Global discovery, Relaying and NAT traversal (home Wi-Fi only).\n"
                + "3. Add the other device: scan the QR code shown under its device ID.\n"
                + "4. Share your audiobooks folder with it, and add the ignore pattern  *Smart AudioBook Player Statistics\n"
                + "To send a change at once, pause playback: SABP saves the position and Syncthing sends it within seconds.");
        String installed = found;
        if (installed != null) {
            st.addView(linkRow(EppyIcon.OPEN_IN_NEW, "Open Syncthing", "Pair devices, share the folder, see sync status", v -> {
                Intent i = c.getPackageManager().getLaunchIntentForPackage(installed);
                if (i != null) c.startActivity(i);
            }));
        } else {
            st.addView(linkRow(EppyIcon.DOWNLOAD, "Get Syncthing-Fork", "Free and open source, from Google Play or F-Droid", v -> openStore()));
        }
    }

    /** The installed Syncthing app's package, or null. */
    public static String installedSyncthing(android.content.Context c) {
        for (String p : SYNCTHING) if (c.getPackageManager().getLaunchIntentForPackage(p) != null) return p;
        return null;
    }

    private void openStore() {
        String id = SYNCTHING[0];
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable noStore) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/" + id + "/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t2) {
                Toast.makeText(c, "Search for Syncthing-Fork in your app store", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshStatus() {
        EppyPlayer p = host.player();
        EppyBook b = p.book();
        if (b == null) {
            status.setText("Start a book to see what its folder holds.");
            return;
        }
        long hereAt = p.lastPlayedMs();
        String folder = b.folderUri;
        new Thread(() -> {
            OtherDevicePosition there = OtherDevicePosition.read(c, folder);
            String s;
            if (there == null) {
                s = "No saved position in this book's folder yet. SABP saves one when you pause.";
            } else {
                EppyBook.FileItem f = b.file(there.fileName);
                long at = (f != null && f.start >= 0 ? f.start : 0) + there.seconds;
                String when = DateUtils.getRelativeTimeSpanString(there.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                s = "Saved position in the book's folder: " + Ui.time(at * 1000) + ", " + when
                        + (there.lastPlayed > hereAt ? ". Newer than this device's: check the other device to jump." : ". This device is up to date.");
            }
            status.post(() -> status.setText(s));
        }, "eppy-sync").start();
    }
}
