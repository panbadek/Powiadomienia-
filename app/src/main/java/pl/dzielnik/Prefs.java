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
    static final String TG_TOKEN = "tg_token";
    static final String TG_CHAT = "tg_chat";
    static final String TG_BOT = "tg_bot";
    static final String TG_LISTEN = "tg_listen";
    static final String TG_OFFSET = "tg_offset";
    static final String DEST = "dest";

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

    // ---------- Telegram ----------

    static String telegramToken(Context c) {
        return get(c).getString(TG_TOKEN, "").trim();
    }

    /** Numer czatu z użytkownikiem; 0, gdy bot nie jest jeszcze połączony z żadnym czatem. */
    static long telegramChat(Context c) {
        return get(c).getLong(TG_CHAT, 0);
    }

    /** Telegram jest gotowy, gdy jest poprawny token i znany czat. */
    static boolean telegramReady(Context c) {
        return Telegram.validToken(telegramToken(c)) && telegramChat(c) != 0;
    }

    /** Czy użytkownik chce, żeby aplikacja nasłuchiwała pytań z Telegrama. */
    static boolean telegramListen(Context c) {
        return get(c).getBoolean(TG_LISTEN, false);
    }

    static void setTelegramListen(Context c, boolean on) {
        get(c).edit().putBoolean(TG_LISTEN, on).apply();
    }

    /** Od której aktualizacji nasłuchiwać (numer ostatniej przetworzonej + 1); 0 = od najstarszej. */
    static long telegramOffset(Context c) {
        return get(c).getLong(TG_OFFSET, 0);
    }

    /** Zapisuję od razu na dysk (commit): po zabiciu procesu wiadomość nie może wrócić drugi raz. */
    static void setTelegramOffset(Context c, long next) {
        get(c).edit().putLong(TG_OFFSET, next).commit();
    }

    /** Gdzie wysyłać części tekstu i odpowiedzi Gemini (Dest.NOTIFICATIONS, TELEGRAM albo BOTH). */
    static int dest(Context c) {
        return Dest.clean(get(c).getInt(DEST, Dest.NOTIFICATIONS));
    }
}
