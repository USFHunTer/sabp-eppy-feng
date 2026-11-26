package app.morphe.extension.sabp;

import android.content.Context;
import android.net.Uri;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;

/**
 * The other device's position: SABP's {@code position.sabp.dat} in the book folder, last written by whichever
 * device played the book last and brought here by a folder-sync app (docs/adr/0002). SABP only reads that file for
 * a book new to its library, so eppy reads it and offers the jump. Part of the "Sync between devices" patch.
 */
public final class OtherDevicePosition {
    public static final String FILE = "position.sabp.dat";
    static final int MIN_GAP_SECONDS = 30;
    private static final String SABP = "ak.alizandro.smartaudiobookplayer.";

    public final String fileName;
    public final int seconds;
    public final long lastPlayed;

    OtherDevicePosition(String fileName, int seconds, long lastPlayed) {
        this.fileName = fileName;
        this.seconds = seconds;
        this.lastPlayed = lastPlayed;
    }

    /** True when the "Sync between devices" patch is applied. */
    public static boolean enabled(Context c) {
        return "on".equals(EppyTheme.configString(c, "eppy_device_sync", "off"));
    }

    /** What the book folder's position says compared with this device: no file, not newer, the same place, or an offer. */
    public static final int NONE = 0, OLDER = 1, SAME_PLACE = 2, OFFER = 3;

    /**
     * OFFER when the other device's position is worth offering here: it was played later than this device played the
     * book, and it's in another file or more than 30 s away.
     */
    public static int compare(String hereFile, int hereSeconds, long hereLastPlayed, OtherDevicePosition there) {
        if (there == null || there.fileName == null) return NONE;
        if (there.lastPlayed <= hereLastPlayed) return OLDER;
        return !there.fileName.equals(hereFile) || Math.abs(there.seconds - hereSeconds) > MIN_GAP_SECONDS ? OFFER : SAME_PLACE;
    }

    /** Reads the book folder's position file; null when there is none or it can't be read. Not on the main thread. */
    public static OtherDevicePosition read(Context c, String folderUri) {
        Uri uri = app.morphe.extension.sabp.read.SpeechSync.fileUri(folderUri, FILE);
        try (InputStream in = "file".equals(uri.getScheme()) ? new FileInputStream(uri.getPath()) : c.getContentResolver().openInputStream(uri)) {
            return in != null ? parse(in) : null;
        } catch (FileNotFoundException e) {
            return null;
        } catch (Throwable t) {
            EppyDiag.log("sync", "position file unreadable: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** Parses SABP's BookDataBackup (Java serialization), or returns null if the file holds something else. */
    static OtherDevicePosition parse(InputStream in) throws Exception {
        try (ObjectInputStream o = new ObjectInputStream(in) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass d) throws IOException, ClassNotFoundException {
                // The file arrives from another device: only SABP's own classes and plain Java values.
                if (!allowed(d.getName())) throw new InvalidClassException(d.getName(), "not allowed in " + FILE);
                return Class.forName(d.getName(), false, OtherDevicePosition.class.getClassLoader());
            }
        }) {
            Object b = o.readObject();
            if (b == null || !b.getClass().getName().equals(SABP + "BookDataBackup")) return null;
            Object file = field(b, "mFileName"), pos = field(b, "mFilePosition"), at = field(b, "mLastPlaybackTime");
            if (!(file instanceof String) || !(pos instanceof Integer) || !(at instanceof Long)) return null;
            return new OtherDevicePosition((String) file, Math.max(0, (Integer) pos), (Long) at);
        }
    }

    static boolean allowed(String name) {
        if (name.startsWith(SABP) || name.startsWith("[L" + SABP)) return true;
        if (name.length() == 2 && name.charAt(0) == '[' && "ZBCSIJFD".indexOf(name.charAt(1)) >= 0) return true;
        switch (name) {
            case "java.lang.Enum":
            case "java.lang.Number":
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Float":
            case "java.lang.Double":
            case "java.lang.Boolean":
            case "java.util.Date":
            case "java.util.ArrayList":
                return true;
            default:
                return false;
        }
    }

    private static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
