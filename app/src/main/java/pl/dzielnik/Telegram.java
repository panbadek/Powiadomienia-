package pl.dzielnik;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rozmowa z Telegramem przez Bot API: odbieranie wiadomości (getUpdates) i wysyłanie (sendMessage).
 * Czysta Java, bez Androida, dzięki temu cały obieg da się sprawdzić na sztucznym serwerze HTTP.
 *
 * Po co to w Dzielniku: zegarek Huawei pozwala odpowiadać na powiadomienia tylko z wybranych
 * komunikatorów, a Telegram jest wśród nich. Części tekstu i odpowiedzi Gemini mogą więc iść jako
 * wiadomości od bota, a odpowiedź napisana na zegarku wraca do Dzielnika jako wiadomość do bota.
 */
final class Telegram {

    /** Adres Bot API. Nie jest stały tylko po to, żeby testy mogły skierować aplikację na sztuczny serwer. */
    static volatile String DEFAULT_BASE = "https://api.telegram.org";

    /** Telegram przyjmuje najwyżej 4096 znaków w jednej wiadomości. */
    static final int MAX_TEXT = 4096;

    /** Część dłuższa niż to jest dzielona dalej; zostaje zapas na numer części w pierwszej linii. */
    static final int PART_LIMIT = 3900;

    static final String GREETING = "Połączono z Dzielnikiem. Napisz tu pytanie, a Dzielnik zapyta Gemini i odeśle "
            + "odpowiedź. Komenda /nowa zaczyna nową rozmowę.";

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Pattern TOKEN = Pattern.compile("\\d{3,}:[A-Za-z0-9_-]{20,100}");
    private static final Pattern COMMAND = Pattern.compile("[a-z0-9_]{1,32}");

    private Telegram() {}

    /** Wiadomość odebrana przez bota (albo inna aktualizacja, z której interesuje mnie tylko numer). */
    static final class Update {
        final long id;
        /** Czat, z którego przyszła wiadomość; 0, gdy to nie jest zwykła wiadomość. */
        final long chatId;
        /** "private", "group", "supergroup" albo "channel". */
        final String chatType;
        final String chatName;
        final boolean fromBot;
        /** Tekst wiadomości; null dla zdjęć, naklejek, głosówek itp. */
        final String text;
        /** Treść wiadomości, na którą ta jest odpowiedzią (gdy użytkownik odpowiada na konkretną wiadomość). */
        final String quoted;
        final boolean quotedFromBot;

        Update(long id, long chatId, String chatType, String chatName, boolean fromBot, String text,
               String quoted, boolean quotedFromBot) {
            this.id = id;
            this.chatId = chatId;
            this.chatType = chatType;
            this.chatName = chatName;
            this.fromBot = fromBot;
            this.text = text;
            this.quoted = quoted;
            this.quotedFromBot = quotedFromBot;
        }
    }

    /** Odpowiedź Telegrama na jedno wywołanie: wynik albo opis błędu po polsku. */
    static final class Reply {
        /** Kod HTTP albo error_code z Telegrama; 0, gdy nie doszło do zrozumiałej odpowiedzi serwera. */
        final int code;
        /** Opis błędu po polsku albo null, gdy wszystko się udało. */
        final String error;
        /** Za ile sekund Telegram pozwala ponowić (parameters.retry_after); 0, gdy brak. */
        final int retryAfter;
        /** Pole "result" odpowiedzi (Map, List, ...). */
        final Object result;

        private Reply(int code, String error, int retryAfter, Object result) {
            this.code = code;
            this.error = error;
            this.retryAfter = retryAfter;
            this.result = result;
        }

        static Reply ok(int code, Object result) {
            return new Reply(code, null, 0, result);
        }

        static Reply fail(int code, String error, int retryAfter) {
            return new Reply(code, error, retryAfter, null);
        }

        boolean ok() {
            return error == null;
        }
    }

    /** Wynik getUpdates. */
    static final class Updates {
        final String error;
        final int code;
        final int retryAfter;
        final List<Update> list;
        /** Największy update_id z odpowiedzi (0, gdy lista jest pusta). */
        final long lastId;

        private Updates(String error, int code, int retryAfter, List<Update> list, long lastId) {
            this.error = error;
            this.code = code;
            this.retryAfter = retryAfter;
            this.list = list;
            this.lastId = lastId;
        }

        static Updates ok(List<Update> list, long lastId) {
            return new Updates(null, 200, 0, list, lastId);
        }

        static Updates fail(int code, String error, int retryAfter) {
            return new Updates(error, code, retryAfter, new ArrayList<>(), 0);
        }

        boolean ok() {
            return error == null;
        }
    }

    interface Stop {
        boolean stopped();
    }

    interface Progress {
        void sent(int done, int total);
    }

    // ------------------------------------------------------------ token i komendy

    /** Wyciąga token z wklejonego tekstu (ze spacjami, z przedrostkiem „bot”, z całym adresem). */
    static String cleanToken(String raw) {
        if (raw == null) return "";
        Matcher m = TOKEN.matcher(raw);
        if (m.find()) return m.group();
        return raw.trim();
    }

    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** „/nowa”, „/nowa@MojBot” albo „/nowa coś” daje „nowa”; zwykły tekst daje null. */
    static String command(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 2 || t.charAt(0) != '/') return null;
        int end = 1;
        while (end < t.length() && !Character.isWhitespace(t.charAt(end)) && t.charAt(end) != '@') end++;
        String name = t.substring(1, end).toLowerCase(Locale.ROOT);
        return COMMAND.matcher(name).matches() ? name : null;
    }

    // ------------------------------------------------------------ wiadomości do wysłania

    /** Części dłuższe niż mieści jedna wiadomość dzielę dalej. */
    static List<String> fit(List<String> parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.length() <= PART_LIMIT) out.add(p);
            else out.addAll(Splitter.split(p, PART_LIMIT));
        }
        return out;
    }

    /**
     * Teksty wiadomości w kolejności wysyłania. Numer części (np. „Część 3/12”) stoi w pierwszej linii.
     * Przy odwróconej kolejności zaczynam od ostatniej części, tak samo jak przy powiadomieniach.
     *
     * @param numberSingle czy pojedyncza część też ma numer
     */
    static List<String> format(List<String> parts, String prefix, boolean numberSingle, boolean reverse) {
        int total = parts.size();
        List<String> out = new ArrayList<>(total);
        for (int k = 0; k < total; k++) {
            int i = reverse ? total - 1 - k : k;
            String text = parts.get(i);
            out.add(total == 1 && !numberSingle ? text : prefix + " " + (i + 1) + "/" + total + "\n" + text);
        }
        return out;
    }

    // ------------------------------------------------------------ wywołania API

    static Reply getMe(String base, String token, int connectMs, int readMs) {
        return post(base, token, "getMe", "{}", connectMs, readMs);
    }

    /** „@nazwa” bota z odpowiedzi getMe (albo jego imię, gdy nie ma nazwy). */
    static String botLabel(Reply me) {
        Map<String, Object> bot = me == null ? null : Json.asObject(me.result);
        if (bot == null) return "bot";
        String username = Json.asString(bot.get("username"));
        if (username != null && !username.isEmpty()) return "@" + username;
        String name = Json.asString(bot.get("first_name"));
        return name == null || name.isEmpty() ? "bot" : name;
    }

    /**
     * Czeka na nowe wiadomości do bota (długie odpytywanie).
     *
     * @param offset     numer pierwszej aktualizacji do odebrania (o 1 większy niż ostatnia przetworzona); 0 = od najstarszej
     * @param timeoutSec jak długo serwer ma czekać na wiadomość; 0 = odpowiedz od razu
     */
    static Updates getUpdates(String base, String token, long offset, int timeoutSec, int connectMs, int readMs) {
        StringBuilder b = new StringBuilder("{");
        if (offset > 0) b.append("\"offset\":").append(offset).append(',');
        b.append("\"limit\":20,\"timeout\":").append(Math.max(0, timeoutSec)).append(",\"allowed_updates\":[\"message\"]}");
        Reply r = post(base, token, "getUpdates", b.toString(), connectMs, readMs);
        if (!r.ok()) return Updates.fail(r.code, r.error, r.retryAfter);
        return parseUpdates(r.result);
    }

    static Reply sendMessage(String base, String token, long chatId, String text, int connectMs, int readMs) {
        String t = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        String body = "{\"chat_id\":" + chatId + ",\"text\":" + Json.quote(t)
                + ",\"link_preview_options\":{\"is_disabled\":true}}";
        return post(base, token, "sendMessage", body, connectMs, readMs);
    }

    /**
     * Wysyła wiadomości po kolei, z przerwą między nimi. Telegram pozwala mniej więcej na jedną wiadomość
     * na sekundę do jednego czatu, a przy przekroczeniu prosi o zwolnienie (429); wtedy czekam i ponawiam.
     *
     * @param netRetryDelaysMs przerwy przed ponowieniem po chwilowym błędzie sieci albo serwera
     * @return opis błędu po polsku albo null, gdy wszystko poszło (także po zatrzymaniu na prośbę użytkownika)
     */
    static String sendAll(String base, String token, long chatId, List<String> messages, long delayMs,
                          Stop stop, Progress progress, int[] netRetryDelaysMs) throws InterruptedException {
        List<String> all = new ArrayList<>();
        for (String m : messages) {
            if (m.length() <= MAX_TEXT) all.add(m);
            else all.addAll(Splitter.split(m, PART_LIMIT));
        }
        int total = all.size();
        for (int k = 0; k < total; k++) {
            if (stop.stopped()) return null;
            Reply r = sendOne(base, token, chatId, all.get(k), netRetryDelaysMs);
            if (!r.ok()) return r.error;
            progress.sent(k + 1, total);
            if (k + 1 < total) Thread.sleep(delayMs);
        }
        return null;
    }

    private static Reply sendOne(String base, String token, long chatId, String text, int[] netRetryDelaysMs)
            throws InterruptedException {
        int net = 0;
        int limited = 0;
        while (true) {
            Reply r = sendMessage(base, token, chatId, text, 15000, 30000);
            if (r.ok()) return r;
            if (r.code == 429 && limited < 3) {
                limited++;
                Thread.sleep(Math.min(60, r.retryAfter > 0 ? r.retryAfter : 2) * 1000L);
                continue;
            }
            if ((r.code == 0 || r.code >= 500) && net < netRetryDelaysMs.length) {
                Thread.sleep(netRetryDelaysMs[net++]);
                continue;
            }
            return r;
        }
    }

    // ------------------------------------------------------------ łączenie z botem

    /** Wynik łączenia z botem. */
    static final class Link {
        /** Opis błędu po polsku albo null. */
        final String error;
        final String bot;
        /** Czat z użytkownikiem; 0, gdy nikt jeszcze nie napisał do bota. */
        final long chatId;
        final String chatName;
        /** Od której aktualizacji nasłuchiwać (starsze pomijam); 0 = bez zmian. */
        final long nextOffset;

        private Link(String error, String bot, long chatId, String chatName, long nextOffset) {
            this.error = error;
            this.bot = bot;
            this.chatId = chatId;
            this.chatName = chatName;
            this.nextOffset = nextOffset;
        }

        boolean linked() {
            return error == null && chatId != 0;
        }
    }

    /**
     * Sprawdza token, szuka czatu użytkownika (ostatnia prywatna wiadomość do bota) i wysyła tam powitanie,
     * żeby od razu było widać, że wysyłanie działa. Starsze wiadomości (np. samo „/start”) są pomijane.
     */
    static Link link(String base, String token, int connectMs, int readMs) {
        Reply me = getMe(base, token, connectMs, readMs);
        if (!me.ok()) return new Link(me.error, null, 0, "", 0);
        String bot = botLabel(me);
        Updates u = getUpdates(base, token, 0, 0, connectMs, readMs);
        if (!u.ok()) return new Link(u.error, bot, 0, "", 0);
        Update chat = null;
        for (Update x : u.list) {
            if (x.chatId != 0 && "private".equals(x.chatType) && !x.fromBot) chat = x;
        }
        if (chat == null) return new Link(null, bot, 0, "", 0);
        Reply hi = sendMessage(base, token, chat.chatId, GREETING, connectMs, readMs);
        if (!hi.ok()) return new Link(hi.error, bot, 0, "", 0);
        return new Link(null, bot, chat.chatId, chat.chatName, u.lastId > 0 ? u.lastId + 1 : 0);
    }

    // ------------------------------------------------------------ odczyt odpowiedzi

    /** Zamienia odpowiedź serwera (kod HTTP i treść) na wynik albo opis błędu. */
    static Reply parseReply(int status, String body) {
        Map<String, Object> root = null;
        try {
            root = Json.asObject(Json.parse(body));
        } catch (RuntimeException ignored) {
            // niżej zgłaszam błąd z początkiem treści
        }
        if (status == 200 && root != null && Boolean.TRUE.equals(root.get("ok"))) {
            return Reply.ok(status, root.get("result"));
        }
        if (root == null && status == 200) {
            return Reply.fail(0, "Telegram odpowiedział czymś, czego nie rozumiem: " + clip(body, 150), 0);
        }
        int code = status;
        int retry = 0;
        String description = null;
        if (root != null) {
            Long ec = asLong(root.get("error_code"));
            if (ec != null && ec > 0 && ec < 1000) code = (int) (long) ec;
            description = Json.asString(root.get("description"));
            Map<String, Object> params = Json.asObject(root.get("parameters"));
            if (params != null) {
                Long ra = asLong(params.get("retry_after"));
                if (ra != null && ra >= 0 && ra < 100000) retry = (int) (long) ra;
            }
        }
        return Reply.fail(code, describeError(code, description == null ? clip(body, 200) : description, retry), retry);
    }

    static Updates parseUpdates(Object result) {
        List<Object> arr = Json.asArray(result);
        if (arr == null) return Updates.fail(0, "Telegram odpowiedział czymś, czego nie rozumiem.", 0);
        List<Update> list = new ArrayList<>();
        long last = 0;
        for (Object o : arr) {
            Map<String, Object> u = Json.asObject(o);
            if (u == null) continue;
            Long id = asLong(u.get("update_id"));
            if (id == null) continue;
            last = Math.max(last, id);
            list.add(toUpdate(id, Json.asObject(u.get("message"))));
        }
        return Updates.ok(list, last);
    }

    private static Update toUpdate(long id, Map<String, Object> m) {
        Map<String, Object> chat = m == null ? null : Json.asObject(m.get("chat"));
        Long chatId = chat == null ? null : asLong(chat.get("id"));
        if (chatId == null) return new Update(id, 0, "", "", false, null, null, false);
        String type = Json.asString(chat.get("type"));
        Map<String, Object> from = Json.asObject(m.get("from"));
        boolean fromBot = from != null && Boolean.TRUE.equals(from.get("is_bot"));
        Map<String, Object> q = Json.asObject(m.get("reply_to_message"));
        Map<String, Object> qFrom = q == null ? null : Json.asObject(q.get("from"));
        return new Update(id, chatId, type == null ? "" : type, chatName(chat), fromBot,
                Json.asString(m.get("text")), q == null ? null : Json.asString(q.get("text")),
                qFrom != null && Boolean.TRUE.equals(qFrom.get("is_bot")));
    }

    private static String chatName(Map<String, Object> chat) {
        String first = Json.asString(chat.get("first_name"));
        String last = Json.asString(chat.get("last_name"));
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        if (!full.isEmpty()) return full;
        String user = Json.asString(chat.get("username"));
        if (user != null && !user.isEmpty()) return "@" + user;
        String title = Json.asString(chat.get("title"));
        return title == null ? "" : title;
    }

    private static String describeError(int code, String description, int retry) {
        String low = description.toLowerCase(Locale.ROOT);
        String detail = description.isEmpty() ? "" : " Serwer napisał: " + clip(description, 300);
        switch (code) {
            case 401:
                return "Telegram odrzucił token bota (401). Skopiuj go jeszcze raz z @BotFather.";
            case 404:
                return "Telegram nie zna takiego bota (404). Sprawdź token.";
            case 409:
                if (low.contains("webhook")) {
                    return "Ten bot ma ustawiony webhook, więc Dzielnik nie może odbierać jego wiadomości (409). "
                            + "Utwórz nowego bota w @BotFather.";
                }
                return "Ten bot jest już odbierany przez inny program albo drugą kopię aplikacji (409). "
                        + "Wyłącz tamten program.";
            case 400:
                if (low.contains("chat not found")) {
                    return "Telegram nie zna tego czatu (400). Napisz do bota /start i naciśnij „Połącz” jeszcze raz.";
                }
                return "Telegram odrzucił zapytanie (400)." + detail;
            case 403:
                return "Telegram nie pozwala botowi pisać do tego czatu (403). Odblokuj bota i napisz do niego /start."
                        + detail;
            case 429:
                return "Telegram prosi o zwolnienie (429)." + (retry > 0 ? " Poczekaj " + retry + " s." : "");
            default:
                if (code >= 500) return "Serwer Telegrama ma awarię (" + code + "). Spróbuj za chwilę." + detail;
                return "Telegram zwrócił błąd " + code + "." + detail;
        }
    }

    // ------------------------------------------------------------ HTTP

    private static Reply post(String base, String token, String method, String json, int connectMs, int readMs) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Reply r;
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(base + "/bot" + token + "/" + method)
                    .toURL().openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                r = parseReply(status, in == null ? "" : readAll(in));
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            r = Reply.fail(0, "Nie udało się połączyć z Telegramem: " + describe(e) + ".", 0);
        } catch (IllegalArgumentException e) {
            r = Reply.fail(401, "Token bota ma niedozwolone znaki. Skopiuj go jeszcze raz z @BotFather.", 0);
        }
        return redact(r, token);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (out.size() + n > MAX_RESPONSE_BYTES) throw new IOException("odpowiedź jest zbyt duża");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String describe(IOException e) {
        if (e instanceof UnknownHostException) return "brak dostępu do internetu";
        if (e instanceof SocketTimeoutException) return "serwer nie odpowiedział na czas";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + clip(m, 120));
    }

    /** Token bota jest hasłem: nie może trafić do żadnego komunikatu (adres z tokenem bywa w treści wyjątków). */
    private static Reply redact(Reply r, String token) {
        if (r.ok() || token.isEmpty() || r.error == null || !r.error.contains(token)) return r;
        return Reply.fail(r.code, r.error.replace(token, "***"), r.retryAfter);
    }

    static Long asLong(Object o) {
        if (!(o instanceof Double)) return null;
        double d = (Double) o;
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 9007199254740992.0) return null;
        return (long) d;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
