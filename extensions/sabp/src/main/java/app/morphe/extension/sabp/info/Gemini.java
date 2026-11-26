package app.morphe.extension.sabp.info;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.sabp.EppyDiag;
import app.morphe.extension.sabp.EppyFlags;

/**
 * Suggested character notes from Google's Gemini API, with the user's own key (Settings → AI
 * character notes). Plain HTTPS, no library. The key goes in a header, never in a URL or the log.
 */
public final class Gemini {
    static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    /** Used when Google's model list can't be read. */
    static final String FALLBACK_LITE = "gemini-flash-lite-latest", FALLBACK_FLASH = "gemini-flash-latest";

    public static final class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }
    }

    private Gemini() {
    }

    // ---- settings (eppy_ai: not part of settings backups, so the key never leaves the phone that way) ----

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("eppy_ai", Context.MODE_PRIVATE);
    }

    /** On in Settings, not switched off in Diagnostics, and a key entered. */
    public static boolean ready(Context c) {
        return prefs(c).getBoolean("on", false) && EppyFlags.on(c, EppyFlags.AI_NOTES) && !key(c).isEmpty();
    }

    public static String key(Context c) {
        return prefs(c).getString("key", "").trim();
    }

    /** Flash-Lite (default) or Flash. */
    public static boolean lite(Context c) {
        return prefs(c).getBoolean("lite", true);
    }

    // ---- requests ---------------------------------------------------------------------------------

    /** A note from the book's own text: only the excerpts the listener has heard. */
    static JSONObject bookRequest(String title, String name, List<String> excerpts) throws Exception {
        String system = "You write notes for a reader's list of characters in a book. Write one line of at most 15 words "
                + "saying who " + name + " is in this story. Use only the excerpts you are given: they are everything the reader "
                + "has heard so far. Add nothing from anywhere else and nothing that happens later. If the excerpts say little, "
                + "describe only what they show. Reply with the line only.";
        StringBuilder user = new StringBuilder();
        if (title != null && !title.isEmpty()) user.append("Book: ").append(title).append('\n');
        user.append("Character: ").append(name).append("\n\nExcerpts:\n");
        for (String e : excerpts) user.append("\n").append(e).append('\n');
        return request(system, user.toString(), false);
    }

    /** General information found with Google Search (may spoil). */
    static JSONObject webRequest(String title, String author, String name) throws Exception {
        String book = (title != null ? title : "this book") + (author != null && !author.isEmpty() ? " by " + author : "");
        String system = "You write notes for a reader's list of characters in a book. Write one line of at most 15 words "
                + "saying who the character is: their role in the story and who they are to the main characters. "
                + "Search the web. Reply with the line only.";
        return request(system, "Character: " + name + "\nBook: " + book, true);
    }

    private static JSONObject request(String system, String user, boolean search) throws Exception {
        JSONObject o = new JSONObject();
        o.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        o.put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", user)))));
        if (search) o.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        return o;
    }

    // ---- responses --------------------------------------------------------------------------------

    /** The answer as one line: first non-empty line, without quotes or markdown, at most 160 characters. */
    static String noteFrom(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            JSONObject fb = o.optJSONObject("promptFeedback");
            String why = fb != null ? fb.optString("blockReason", "") : "";
            throw new Failure("Gemini didn't answer" + (why.isEmpty() ? "" : " (" + why.toLowerCase(java.util.Locale.ROOT) + ")"));
        }
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content != null ? content.optJSONArray("parts") : null;
        StringBuilder text = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                if (!p.optBoolean("thought", false)) text.append(p.optString("text", ""));
            }
        }
        for (String line : text.toString().split("\n")) {
            String s = line.replaceAll("[*_#`]", "").trim().replaceAll("^[\"“”']+|[\"“”']+$", "").trim();
            if (s.isEmpty()) continue;
            if (s.length() > 160) {
                int cut = s.lastIndexOf(' ', 159);
                s = s.substring(0, cut > 80 ? cut : 159) + "…";
            }
            return s;
        }
        throw new Failure("Gemini sent an empty answer");
    }

    /** A short message for an HTTP error from Gemini. */
    static String errorMessage(int code, String body) {
        String msg = "";
        try {
            msg = new JSONObject(body).getJSONObject("error").optString("message", "");
        } catch (Throwable ignored) {
        }
        if (code == 429) return "Google's free limit is used up for now. Try again later.";
        if (msg.toLowerCase(java.util.Locale.ROOT).contains("api key")) return "The API key isn't valid. Check it in Settings → AI character notes.";
        if (code == 403) return "This key isn't allowed to use Gemini (Settings → AI character notes).";
        if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
        return "Gemini error " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    private static final Pattern MODEL = Pattern.compile("^models/(gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?)$");

    /** The newest stable Flash-Lite (or Flash) model in Google's list; null if none. */
    static String pickModel(String listJson, boolean lite) throws Exception {
        JSONArray models = new JSONObject(listJson).optJSONArray("models");
        String best = null;
        double bestV = -1;
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            Matcher mm = MODEL.matcher(m.optString("name"));
            if (!mm.matches() || (mm.group(3) != null) != lite) continue;
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            if (methods == null || !methods.toString().contains("\"generateContent\"")) continue;
            double v = Double.parseDouble(mm.group(2));
            if (v > bestV) {
                bestV = v;
                best = mm.group(1);
            }
        }
        return best;
    }

    // ---- network ----------------------------------------------------------------------------------

    /** A suggested note for the book's excerpts. Blocking: call off the main thread. */
    public static String fromBook(Context c, String title, String name, List<String> excerpts) throws Exception {
        return noteFrom(post(c, bookRequest(title, name, excerpts)));
    }

    /** A suggested note from a web search. Blocking. */
    public static String fromWeb(Context c, String title, String author, String name) throws Exception {
        return noteFrom(post(c, webRequest(title, author, name)));
    }

    /** One tiny request to check the key and the model. Blocking. Returns the model used. */
    public static String test(Context c) throws Exception {
        prefs(c).edit().remove(modelKey(c)).apply(); // look the model up again
        JSONObject r = request("Reply with the word OK.", "OK?", false);
        noteFrom(post(c, r));
        return model(c);
    }

    private static String modelKey(Context c) {
        return lite(c) ? "model:lite" : "model:flash";
    }

    /** The model id, looked up in Google's list once and remembered. */
    static String model(Context c) {
        String k = modelKey(c);
        String m = prefs(c).getString(k, null);
        if (m != null) return m;
        try {
            m = pickModel(http(c, BASE + "models?pageSize=1000", null), lite(c));
        } catch (Throwable e) {
            EppyDiag.log("ai", "model list: " + e.getMessage());
        }
        if (m == null) return lite(c) ? FALLBACK_LITE : FALLBACK_FLASH;
        prefs(c).edit().putString(k, m).apply();
        return m;
    }

    private static String post(Context c, JSONObject body) throws Exception {
        if (key(c).isEmpty()) throw new Failure("Enter your Gemini API key in Settings → AI character notes.");
        return http(c, BASE + "models/" + model(c) + ":generateContent", body.toString());
    }

    private static String http(Context c, String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(60_000);
        con.setRequestProperty("x-goog-api-key", key(c));
        con.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code;
            try {
                code = con.getResponseCode();
            } catch (java.io.IOException e) {
                throw new Failure("No connection to Google. Check the internet connection.");
            }
            String text = read(code / 100 == 2 ? con.getInputStream() : con.getErrorStream());
            if (code / 100 != 2) {
                String m = errorMessage(code, text);
                EppyDiag.log("ai", "HTTP " + code + ": " + m);
                throw new Failure(m);
            }
            return text;
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && b.length() < 2_000_000) b.append(buf, 0, n);
            return b.toString();
        }
    }
}
