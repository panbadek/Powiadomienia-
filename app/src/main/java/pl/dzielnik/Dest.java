package pl.dzielnik;

/**
 * Gdzie wysyłać części tekstu i odpowiedzi Gemini: powiadomienia Dzielnika, Telegram albo jedno i drugie.
 * Czysta Java, żeby tabelę wyborów dało się sprawdzić testem.
 */
final class Dest {

    static final int NOTIFICATIONS = 0;
    static final int TELEGRAM = 1;
    static final int BOTH = 2;

    static final int F_NOTIFICATIONS = 1;
    static final int F_TELEGRAM = 2;

    private Dest() {}

    static int clean(int configured) {
        return configured == TELEGRAM || configured == BOTH ? configured : NOTIFICATIONS;
    }

    /**
     * @param configured    wybór z ustawień
     * @param telegramReady czy Telegram jest połączony (token i czat)
     * @param fromTelegram  czy pytanie przyszło z Telegrama; odpowiedź wraca wtedy do Telegrama,
     *                      a powiadomienia dochodzą tylko przy wyborze „Oba”
     * @return suma flag F_NOTIFICATIONS i F_TELEGRAM; bez połączonego Telegrama zawsze same powiadomienia
     */
    static int flags(int configured, boolean telegramReady, boolean fromTelegram) {
        int c = clean(configured);
        if (!telegramReady) return F_NOTIFICATIONS;
        if (fromTelegram) return c == BOTH ? F_NOTIFICATIONS | F_TELEGRAM : F_TELEGRAM;
        switch (c) {
            case TELEGRAM:
                return F_TELEGRAM;
            case BOTH:
                return F_NOTIFICATIONS | F_TELEGRAM;
            default:
                return F_NOTIFICATIONS;
        }
    }
}
