package pl.dzielnik;

/**
 * Pętla nasłuchu: odbiera wiadomości do bota i przekazuje te z właściwego czatu dalej.
 * Czysta Java, bez Androida, dzięki temu da się ją sprawdzić na sztucznym serwerze.
 *
 * Numer ostatnio przetworzonej wiadomości zapisuje po każdej wiadomości (saveOffset), więc po
 * ponownym uruchomieniu nic nie jest obsługiwane dwa razy, a wiadomości z czasu przerwy nie giną
 * (Telegram trzyma je do 24 godzin).
 */
final class TelegramBridge {

    private TelegramBridge() {}

    interface Host {
        boolean stopped();

        /** Od której aktualizacji pytać Telegram (0 = od najstarszej niepotwierdzonej). */
        long nextOffset();

        void saveOffset(long next);

        /** Wiadomość z właściwego czatu: pytanie, komenda albo coś bez tekstu. Wolno blokować. */
        void message(Telegram.Update u) throws InterruptedException;

        /** Chwilowy kłopot z Telegramem (opis po polsku); null, gdy wszystko znów działa. */
        void problem(String text);

        /** Błąd, po którym dalsze próby nie mają sensu (np. zły token). Pętla kończy się po nim. */
        void fatal(String text);
    }

    /** Czy to wiadomość od właściciela: prywatny czat z zapisanym numerem, nie od bota. */
    static boolean accepts(Telegram.Update u, long chatId) {
        return u.chatId != 0 && u.chatId == chatId && "private".equals(u.chatType) && !u.fromBot;
    }

    /**
     * @param pollSeconds jak długo Telegram ma czekać na wiadomość w jednym zapytaniu
     * @param backoffMs   przerwy między kolejnymi nieudanymi próbami (ostatnia powtarza się w nieskończoność)
     * @param idleGuardMs gdy zapytanie wróci puste i szybciej niż po tylu ms, czekam tyle samo,
     *                    żeby błędnie działający serwer nie obciążył telefonu pętlą bez przerwy
     */
    static void run(String base, String token, long chatId, Host host, int pollSeconds, int[] backoffMs,
                    int idleGuardMs) throws InterruptedException {
        int failures = 0;
        while (!host.stopped()) {
            long started = System.nanoTime();
            Telegram.Updates r = Telegram.getUpdates(base, token, host.nextOffset(), pollSeconds, 15000,
                    (pollSeconds + 20) * 1000);
            if (host.stopped()) return;

            if (!r.ok()) {
                if (r.code == 401 || r.code == 404) {
                    host.fatal(r.error);
                    return;
                }
                failures++;
                host.problem(r.error);
                long wait = r.retryAfter > 0
                        ? Math.min(60, r.retryAfter) * 1000L
                        : backoffMs[Math.min(failures - 1, backoffMs.length - 1)];
                Thread.sleep(wait);
                continue;
            }
            if (failures > 0) {
                failures = 0;
                host.problem(null);
            }

            for (Telegram.Update u : r.list) {
                if (host.stopped()) return;
                if (accepts(u, chatId)) {
                    try {
                        host.message(u);
                    } catch (RuntimeException e) {
                        host.problem("Nieoczekiwany błąd przy obsłudze wiadomości: " + e);
                    }
                }
                host.saveOffset(u.id + 1);
            }

            long tookMs = (System.nanoTime() - started) / 1_000_000;
            if (r.list.isEmpty() && tookMs < idleGuardMs) Thread.sleep(idleGuardMs);
        }
    }
}
