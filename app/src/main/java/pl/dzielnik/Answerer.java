package pl.dzielnik;

import android.content.Context;

import java.util.List;

/**
 * Jedno pytanie do Gemini od początku do końca: pamięć rozmowy, zapytanie, podział odpowiedzi na części
 * i wysłanie ich tam, gdzie wybrał użytkownik (powiadomienia, Telegram albo oba).
 * Korzystają z tego i GeminiService (pytania z powiadomień i z aplikacji), i TelegramService (pytania z Telegrama).
 */
final class Answerer {

    private Answerer() {}

    /** Jedna rozmowa: pytania obsługuję po kolei, bez względu na to, skąd przyszły. */
    private static final Object QUESTION_LOCK = new Object();

    /** Chroni pamięć rozmowy. Trzymany tylko na chwilę, więc „Zacznij nową rozmowę” nigdy nie czeka na Gemini. */
    private static final Object HISTORY_LOCK = new Object();

    /** Rośnie przy każdej nowej rozmowie; pytanie zadane przed nią nie wraca potem do pamięci. */
    private static int generation;

    /**
     * Zadaje pytanie i wysyła odpowiedź. Pytania obsługuję po kolei, żeby pamięć rozmowy się nie rozjechała.
     * Metoda blokuje do końca wysyłania odpowiedzi.
     *
     * @param quoted       treść wiadomości lub powiadomienia, na które użytkownik odpowiada (może być pusta)
     * @param fromTelegram czy pytanie przyszło z Telegrama; odpowiedź (i błąd) wraca wtedy do Telegrama
     */
    static void answer(Context c, String question, String quoted, boolean fromTelegram) {
        synchronized (QUESTION_LOCK) {
            ask(c, question, quoted, fromTelegram);
        }
    }

    /** Czyści pamięć rozmowy. Odpowiedź na pytanie, które właśnie trwa, nie trafi do nowej rozmowy. */
    static void newChat(Context c) {
        synchronized (HISTORY_LOCK) {
            generation++;
            Prefs.setHistory(c, "");
        }
    }

    private static void ask(Context c, String question, String quoted, boolean fromTelegram) {
        String key = Prefs.geminiKey(c);
        if (key.isEmpty()) {
            fail(c, "Brak klucza Gemini. Wpisz go w aplikacji Dzielnik, w sekcji Gemini.", fromTelegram);
            return;
        }
        GeminiService.status = "Pytam Gemini…";

        // Treść wiadomości, na którą odpowiadam, idzie do Gemini razem z moją odpowiedzią.
        String userText = Gemini.compose(quoted, question);
        int startedIn;
        List<Gemini.Turn> history;
        synchronized (HISTORY_LOCK) {
            startedIn = generation;
            history = History.parse(Prefs.history(c));
        }
        Gemini.Result result = Gemini.ask(Gemini.DEFAULT_BASE, key, Prefs.geminiModel(c), Gemini.SYSTEM,
                history, userText, 20000, 120000, new int[]{3000, 8000});
        if (!result.ok()) {
            fail(c, result.error, fromTelegram);
            return;
        }
        synchronized (HISTORY_LOCK) {
            if (startedIn == generation) {
                Prefs.setHistory(c, History.toJson(History.add(history, userText, result.text)));
            }
        }

        List<String> parts = Splitter.split(result.text, Prefs.limit(c));
        if (parts.isEmpty()) {
            fail(c, "Gemini zwróciło pustą odpowiedź.", fromTelegram);
            return;
        }
        try {
            deliver(c, parts, fromTelegram);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GeminiService.status = "Przerwano wysyłanie odpowiedzi Gemini.";
        }
    }

    private static void deliver(Context c, List<String> parts, boolean fromTelegram) throws InterruptedException {
        int flags = Dest.flags(Prefs.dest(c), Prefs.telegramReady(c), fromTelegram);
        boolean toTelegram = (flags & Dest.F_TELEGRAM) != 0;
        boolean toNotifications = (flags & Dest.F_NOTIFICATIONS) != 0;
        long delay = Prefs.delayMs(c);
        boolean reverse = Prefs.reverse(c);

        // Stary błąd przestaje być aktualny, bo odpowiedź właśnie przyszła.
        Notifier.manager(c).cancel(Notifier.ID_ERROR);

        String problem = null;
        int sentToTelegram = 0;
        if (toTelegram) {
            List<String> messages = Telegram.format(Telegram.fit(parts), "Gemini", false, reverse);
            sentToTelegram = messages.size();
            GeminiService.status = "Wysyłam odpowiedź Gemini do Telegrama…";
            problem = Telegram.sendAll(Telegram.DEFAULT_BASE, Prefs.telegramToken(c), Prefs.telegramChat(c),
                    messages, delay, () -> false,
                    (done, total) -> GeminiService.status = "Telegram: wysłano " + done + " z " + total + ".",
                    new int[]{2000, 5000});
            if (problem != null) {
                Notifier.postError(c, "Telegram: coś poszło nie tak", problem);
                // Odpowiedź nie może przepaść: gdy Telegram zawiódł, dostaję ją w powiadomieniach.
                toNotifications = true;
            }
        }
        if (toNotifications) {
            GeminiService.status = "Wysyłam odpowiedź Gemini…";
            // Nowa odpowiedź zastępuje poprzednią, żeby w panelu nie mieszały się części z dwóch odpowiedzi.
            Notifier.cancelRange(c, Notifier.ID_GEMINI_BASE, Notifier.ID_GEMINI_END);
            Notifier.postParts(c, parts, "Gemini", false, Notifier.ID_GEMINI_BASE, Notifier.KIND_GEMINI,
                    delay, reverse, new Notifier.Control() {
                        @Override
                        public boolean stopped() {
                            return false;
                        }

                        @Override
                        public void progress(int done, int total, String info) {
                            if (info != null) GeminiService.status = info;
                        }
                    });
        }

        if (problem != null) {
            GeminiService.status = problem + " Odpowiedź jest w powiadomieniach.";
        } else if (toTelegram && toNotifications) {
            GeminiService.status = "Odpowiedź Gemini wysłana do Telegrama i w powiadomieniach.";
        } else if (toTelegram) {
            GeminiService.status = "Odpowiedź Gemini wysłana do Telegrama: " + sentToTelegram + " "
                    + Notifier.wiadomosci(sentToTelegram) + ".";
        } else {
            GeminiService.status = "Odpowiedź Gemini wysłana: " + parts.size() + " "
                    + Notifier.powiadomien(parts.size()) + ".";
        }
    }

    private static void fail(Context c, String message, boolean fromTelegram) {
        GeminiService.status = message;
        Notifier.postError(c, message);
        if (fromTelegram && Prefs.telegramReady(c)) {
            // Użytkownik czeka na zegarku: błąd ma dojść tam, skąd zadał pytanie.
            Telegram.sendMessage(Telegram.DEFAULT_BASE, Prefs.telegramToken(c), Prefs.telegramChat(c),
                    "Gemini: coś poszło nie tak. " + message, 15000, 30000);
        }
    }
}
