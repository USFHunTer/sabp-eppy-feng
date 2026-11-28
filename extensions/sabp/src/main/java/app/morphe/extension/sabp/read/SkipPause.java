package app.morphe.extension.sabp.read;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.sabp.EppyBook;
import app.morphe.extension.sabp.EppyBridge;
import app.morphe.extension.sabp.EppyPlayer;
import app.morphe.extension.sabp.EppyRoute;

/**
 * "Pause at skipped passages": the per-device switch and minimum size, and the watcher that notices
 * crossings while eppy isn't in front (the activity's own tick watches while it is), pauses, and
 * posts a notification with Play and Read.
 */
public final class SkipPause {
    /** Stored minimum size meaning the switch is off. */
    public static final int OFF = -1;
    /** The choices: off, every passage, 50+, 100+, 200+ words. */
    public static final int[] CHOICES = {OFF, 0, 50, 100, 200};
    public static final String EXTRA_FIRST = "eppy_skip_first";
    public static final String EXTRA_LAST = "eppy_skip_last";

    private static final String KEY = "skip_pause_min";
    private static final String CHANNEL = "eppy_skipped";
    private static final int NOTIFICATION_ID = 0x5C1;

    private SkipPause() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("eppy_readalong", Context.MODE_PRIVATE);
    }

    /** OFF, or the smallest passage (in words) that pauses; 0 = every passage. */
    public static int minWords(Context c) {
        return prefs(c).getInt(KEY, OFF);
    }

    public static void setMinWords(Context c, int min) {
        prefs(c).edit().putInt(KEY, min).apply();
        start(c);
    }

    /** Pause for a crossed passage of this many words, rather than only offer to. */
    public static boolean shouldPause(int minWords, int words) {
        return minWords != OFF && words >= minWords;
    }

    public static String describe(int min) {
        return min == OFF ? "Off" : min == 0 ? "On · every passage" : "On · " + min + "+ words";
    }

    public static String label(int min) {
        return min == OFF ? "Off" : min == 0 ? "Every skipped passage" : "Passages of " + min + "+ words";
    }

    // ---- the background watcher -----------------------------------------------------------------

    private static Handler main; // made on first start: a static Handler would break the JVM tests
    private static final SkipWatcher watcher = new SkipWatcher();
    private static Context app;
    private static boolean foreground, running, notified;
    private static long notifiedAt;

    /** eppy's activity is in front: its own tick watches, so this one stands aside. */
    public static void setForeground(Context c, boolean inFront) {
        foreground = inFront;
        if (inFront) watcher.reset();
        else start(c);
    }

    /** Starts the watcher if the switch is on and SABP's player service is running (idempotent). */
    public static void start(Context c) {
        app = c.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (running) return;
        running = true;
        main.post(SkipPause::tick);
    }

    private static void tick() {
        EppyPlayer p = EppyPlayer.get(app);
        boolean playing = EppyBridge.getPlayerService() != null && p.keepSession() && p.isPlaying();
        // Played again (from anywhere): the notice is done. The pause itself takes a moment to land.
        if (notified && playing && SystemClock.elapsedRealtime() - notifiedAt > 2000) cancel(app);
        int min = minWords(app);
        if (EppyBridge.getPlayerService() == null || (min == OFF && !notified)) {
            running = false;
            watcher.reset();
            return;
        }
        main.postDelayed(SkipPause::tick, 1000);
        if (foreground || min == OFF || !playing) {
            watcher.reset();
            return;
        }
        EppyBook b = p.book();
        ReadAlong ra = ReadAlong.get(app);
        if (b == null || !ra.enabled()) {
            watcher.reset();
            return;
        }
        ra.openBook(b.folderUri);
        int[] sk = watcher.update(p.bookPositionSeconds(), true, Math.max(4, 3 * p.speed()), ra::skippedStartingBetween);
        if (sk == null || !shouldPause(min, sk[1] - sk[0] + 1)) return;
        p.playPause();
        notify(app, sk);
    }

    private static void notify(Context c, int[] sk) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Skipped passages", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Playback paused at a passage of the book that isn't in the audiobook");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
        PendingIntent read = PendingIntent.getActivity(c, 0, readIntent(c, sk), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // Paused, so SABP's own play/pause toggle resumes, exactly where the narration carries on.
        PendingIntent play = PendingIntent.getService(c, 1, EppyBridge.serviceIntent(c, EppyBridge.ACTION_PLAY_PAUSE), PendingIntent.FLAG_IMMUTABLE);
        int n = sk[1] - sk[0] + 1;
        Notification note = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Paused at a skipped passage")
                .setContentText(n + " words not in the audiobook")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(read)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Play", play).build())
                .addAction(new Notification.Action.Builder(null, "Read", read).build())
                .build();
        try {
            nm.notify(NOTIFICATION_ID, note);
            notified = true;
            notifiedAt = SystemClock.elapsedRealtime();
        } catch (SecurityException ignored) {
            // No notification permission: the pause still happened.
        }
    }

    /** Opens eppy's player on the Read tab, paused at the passage. */
    public static Intent readIntent(Context c, int[] sk) {
        Intent i = new Intent();
        i.setClassName(c.getPackageName(), "app.morphe.extension.sabp.EppyActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EppyRoute.EXTRA_FORCE_EPPY, true);
        i.putExtra(EXTRA_FIRST, sk[0]);
        i.putExtra(EXTRA_LAST, sk[1]);
        return i;
    }

    public static void cancel(Context c) {
        notified = false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }
}
