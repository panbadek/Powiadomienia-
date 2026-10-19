package pl.dzielnik;

import android.content.Context;
import android.content.SharedPreferences;

/** Ustawienia zapisane w telefonie. Czytają je i ekran aplikacji, i usługi. */
final class Prefs {

    static final String NAME = "dzielnik";
    static final int DEFAULT_LIMIT = 200;

    static final String LIMIT = "limit";
    static final String DELAY = "delay";
    static final String TEST_LEN = "testLen";
    static final String REVERSE = "reverse";
    static final String GEMINI_KEY = "gemini_key";
    static final String GEMINI_MODEL = "gemini_model";
    static final String GEMINI_REPLY = "gemini_reply";
    static final String GEMINI_HISTORY = "gemini_history";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int limit(Context c) {
        int v = get(c).getInt(LIMIT, DEFAULT_LIMIT);
        return Math.max(Splitter.MIN_LIMIT, Math.min(Measure.MAX, v));
    }

    static long delayMs(Context c) {
        return parseDelayMs(get(c).getString(DELAY, "2"));
    }

    /** Przerwa w ms z tekstu; dopuszczam przecinek i kropkę, zakres 0,5–60 s, przy błędzie 2 s. */
    static long parseDelayMs(String s) {
        double sec = 2;
        try {
            if (s != null) sec = Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            // zostaje 2 s
        }
        sec = Math.max(0.5, Math.min(60, sec));
        return Math.round(sec * 1000);
    }

    static boolean reverse(Context c) {
        return get(c).getBoolean(REVERSE, false);
    }

    static String geminiKey(Context c) {
        return get(c).getString(GEMINI_KEY, "").trim();
    }

    static String geminiModel(Context c) {
        return Gemini.cleanModel(get(c).getString(GEMINI_MODEL, Gemini.DEFAULT_MODEL));
    }

    /** Przycisk „Odpowiedz” w powiadomieniach: włączony przełącznikiem i tylko wtedy, gdy jest klucz. */
    static boolean replyEnabled(Context c) {
        SharedPreferences sp = get(c);
        return sp.getBoolean(GEMINI_REPLY, true) && !sp.getString(GEMINI_KEY, "").trim().isEmpty();
    }

    static String history(Context c) {
        return get(c).getString(GEMINI_HISTORY, "");
    }

    static void setHistory(Context c, String json) {
        get(c).edit().putString(GEMINI_HISTORY, json).apply();
    }
}
