package pl.dzielnik;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test części Telegrama bez Androida: token, komendy, składanie wiadomości, odczyt odpowiedzi i błędów,
 * wysyłanie z ponawianiem, łączenie z botem i pętla nasłuchu. Zapytania idą na sztuczny serwer HTTP z JDK,
 * który udaje Bot API (getMe, getUpdates z długim odpytywaniem i potwierdzaniem przez offset, sendMessage).
 *
 * javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/{Json,Splitter,Measure,Telegram,TelegramBridge,Dest}.java tests/pl/dzielnik/TelegramTest.java
 * java -cp out pl.dzielnik.TelegramTest
 */
public class TelegramTest {

    static int checks = 0;

    static void eq(Object expected, Object actual, String what) {
        checks++;
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionError(what + "\n  oczekiwano: " + expected + "\n  jest:       " + actual);
    }

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError(what);
    }

    static void contains(String text, String part, String what) {
        checks++;
        if (text == null || !text.contains(part)) {
            throw new AssertionError(what + "\n  szukano: " + part + "\n  w:       " + text);
        }
    }

    static void notContains(String text, String part, String what) {
        checks++;
        if (text != null && text.contains(part)) {
            throw new AssertionError(what + "\n  nie powinno być: " + part + "\n  w:               " + text);
        }
    }

    static final String TOKEN = "123456789:AAFakeTokenForTests_abcdefghijklmnop-1";
    static final String ECHO_TOKEN = "999999:EchoEchoEchoEchoEchoEcho1234567";
    static final long OWNER = 4242;
    static final int[] FAST = {5, 5};

    // ------------------------------------------------------------ składanie JSON-a udającego Telegram

    static String chatJson(long chatId, String type) {
        if ("private".equals(type)) {
            return "{\"id\":" + chatId + ",\"first_name\":\"Jan\",\"last_name\":\"Kowalski\",\"type\":\"private\"}";
        }
        return "{\"id\":" + chatId + ",\"title\":\"Grupa testowa\",\"type\":\"" + type + "\"}";
    }

    static String msg(long messageId, long chatId, String type, boolean fromBot, String text, String quotedJson) {
        StringBuilder b = new StringBuilder();
        b.append("{\"message_id\":").append(messageId)
                .append(",\"from\":{\"id\":").append(chatId).append(",\"is_bot\":").append(fromBot)
                .append(",\"first_name\":\"Jan\"},\"chat\":").append(chatJson(chatId, type))
                .append(",\"date\":1700000000");
        if (text != null) b.append(",\"text\":").append(Json.quote(text));
        if (quotedJson != null) b.append(",\"reply_to_message\":").append(quotedJson);
        return b.append('}').toString();
    }

    static String upd(long id, String messageJson) {
        return "{\"update_id\":" + id + (messageJson == null ? "" : ",\"message\":" + messageJson) + "}";
    }

    static String quoted(String text, boolean fromBot) {
        return "{\"message_id\":1,\"from\":{\"id\":777,\"is_bot\":" + fromBot + ",\"first_name\":\"Dzielnik\"},"
                + "\"chat\":" + chatJson(OWNER, "private") + ",\"date\":1700000000,\"text\":" + Json.quote(text) + "}";
    }

    static String okEmpty() {
        return "{\"ok\":true,\"result\":[]}";
    }

    // ------------------------------------------------------------ sztuczny serwer

    static final class Seen {
        final String method, path, contentType, body;

        Seen(String method, String path, String contentType, String body) {
            this.method = method;
            this.path = path;
            this.contentType = contentType;
            this.body = body;
        }
    }

    static final class Scripted {
        final String method;
        final int status;
        final String body;
        final long delayMs;

        Scripted(String method, int status, String body, long delayMs) {
            this.method = method;
            this.status = status;
            this.body = body;
            this.delayMs = delayMs;
        }
    }

    static final List<Seen> seen = new CopyOnWriteArrayList<>();
    static final List<Seen> sent = new CopyOnWriteArrayList<>();
    static final TreeMap<Long, String> queue = new TreeMap<>();
    static final ConcurrentLinkedDeque<Scripted> overrides = new ConcurrentLinkedDeque<>();
    static final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    static long longPollMs = 120;

    static void reset() {
        seen.clear();
        sent.clear();
        synchronized (queue) {
            queue.clear();
        }
        overrides.clear();
        attempts.clear();
    }

    static void enqueue(long id, String messageJson) {
        synchronized (queue) {
            queue.put(id, upd(id, messageJson));
        }
    }

    static int count(String method) {
        int n = 0;
        for (Seen s : seen) if (s.path.endsWith("/" + method)) n++;
        return n;
    }

    static void reply(HttpExchange ex, int status, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (IOException ignored) {
            // klient się rozłączył (test przekroczenia czasu)
        }
    }

    static String err(int code, String description) {
        return "{\"ok\":false,\"error_code\":" + code + ",\"description\":" + Json.quote(description) + "}";
    }

    static Scripted takeOverride(String method) {
        for (Scripted o : overrides) {
            if (o.method == null || o.method.equals(method)) {
                if (overrides.remove(o)) return o;
            }
        }
        return null;
    }

    static void handle(HttpExchange ex) {
        try {
            handleInner(ex);
        } catch (Exception e) {
            reply(ex, 500, "{\"ok\":false,\"error_code\":500,\"description\":\"test server: " + e + "\"}");
        }
    }

    static void handleInner(HttpExchange ex) throws Exception {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        try (InputStream is = ex.getRequestBody()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) in.write(buf, 0, n);
        }
        String path = ex.getRequestURI().getPath();
        String body = new String(in.toByteArray(), StandardCharsets.UTF_8);
        Seen s = new Seen(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("Content-Type"), body);
        seen.add(s);

        if (!path.startsWith("/bot") || path.indexOf('/', 4) < 0) {
            reply(ex, 404, err(404, "Not Found"));
            return;
        }
        String rest = path.substring(4);
        String token = rest.substring(0, rest.indexOf('/'));
        String method = rest.substring(rest.indexOf('/') + 1);

        if (token.equals(ECHO_TOKEN)) {
            reply(ex, 400, err(400, "Bad Request: coś nie tak z " + token));
            return;
        }
        if (!token.equals(TOKEN)) {
            reply(ex, 401, err(401, "Unauthorized"));
            return;
        }
        Scripted o = takeOverride(method);
        if (o != null) {
            if (o.delayMs > 0) Thread.sleep(o.delayMs);
            reply(ex, o.status, o.body);
            return;
        }

        switch (method) {
            case "getMe":
                reply(ex, 200, "{\"ok\":true,\"result\":{\"id\":777,\"is_bot\":true,\"first_name\":\"Dzielnik\",\"username\":\"dzielnik_test_bot\"}}");
                break;
            case "getUpdates": {
                Map<String, Object> req = Json.asObject(Json.parse(body));
                Long offset = Telegram.asLong(req.get("offset"));
                Long timeout = Telegram.asLong(req.get("timeout"));
                synchronized (queue) {
                    if (offset != null) queue.headMap(offset).clear();
                }
                long deadline = System.currentTimeMillis() + Math.min((timeout == null ? 0 : timeout) * 1000, longPollMs);
                while (true) {
                    String result = null;
                    synchronized (queue) {
                        if (!queue.isEmpty()) {
                            List<String> raw = new ArrayList<>();
                            for (String u : queue.values()) {
                                if (raw.size() >= 20) break;
                                raw.add(u);
                            }
                            result = "{\"ok\":true,\"result\":[" + String.join(",", raw) + "]}";
                        }
                    }
                    if (result == null && System.currentTimeMillis() >= deadline) result = okEmpty();
                    if (result != null) {
                        reply(ex, 200, result);
                        return;
                    }
                    Thread.sleep(10);
                }
            }
            case "sendMessage": {
                Map<String, Object> req = Json.asObject(Json.parse(body));
                long chat = Telegram.asLong(req.get("chat_id"));
                String text = Json.asString(req.get("text"));
                int n = attempts.computeIfAbsent(text.length() > 20 ? text.substring(0, 20) : text, k -> new AtomicInteger()).incrementAndGet();
                if (chat == 404404) {
                    reply(ex, 400, err(400, "Bad Request: chat not found"));
                } else if (chat == 403403) {
                    reply(ex, 403, err(403, "Forbidden: bot was blocked by the user"));
                } else if (text.contains("[[429]]") && n == 1) {
                    reply(ex, 429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 1\",\"parameters\":{\"retry_after\":1}}");
                } else if (text.contains("[[500]]") && n == 1) {
                    reply(ex, 500, "<html>Internal Server Error</html>");
                } else {
                    sent.add(s);
                    reply(ex, 200, "{\"ok\":true,\"result\":{\"message_id\":" + (100 + sent.size()) + ",\"chat\":" + chatJson(chat, "private") + ",\"date\":1700000001,\"text\":" + Json.quote(text) + "}}");
                }
                break;
            }
            default:
                reply(ex, 404, err(404, "Not Found: " + method));
        }
    }

    static String sentText(int i) {
        return Json.asString(Json.asObject(Json.parse(sent.get(i).body)).get("text"));
    }

    static long sentChat(int i) {
        return Telegram.asLong(Json.asObject(Json.parse(sent.get(i).body)).get("chat_id"));
    }

    // ------------------------------------------------------------ czysta logika

    static void tokenAndCommandTests() {
        String t = "123456789:AAH-fake_TokenValue0123456789abcdefg";
        eq(t, Telegram.cleanToken(t), "token bez zmian");
        eq(t, Telegram.cleanToken("  " + t + " \n"), "spacje wokół");
        eq(t, Telegram.cleanToken("Use this token to access the HTTP API:\n" + t + "\nKeep your token secure"), "token z tekstu od BotFathera");
        eq(t, Telegram.cleanToken("https://api.telegram.org/bot" + t + "/getMe"), "token z adresu");
        eq(t, Telegram.cleanToken("bot" + t), "przedrostek bot");
        eq("", Telegram.cleanToken(null), "null");
        eq("abc", Telegram.cleanToken(" abc "), "bez tokenu zostaje przycięty tekst");
        ok(Telegram.validToken(t), "poprawny token");
        ok(Telegram.validToken(TOKEN), "token testowy");
        ok(!Telegram.validToken(null), "null");
        ok(!Telegram.validToken(""), "pusty");
        ok(!Telegram.validToken("abc"), "bez dwukropka");
        ok(!Telegram.validToken("12:AAH-fake_TokenValue0123456789abcdefg"), "za mało cyfr");
        ok(!Telegram.validToken("123456789:short"), "za krótki sekret");
        ok(!Telegram.validToken(t + " "), "spacja na końcu");
        ok(!Telegram.validToken("123456789:AAH-fake_TokenValue/0123456789abcdefg"), "ukośnik w tokenie");
        ok(!Telegram.validToken("123456789:AAH-fake_TokenValue?x=0123456789abcdefg"), "znak zapytania w tokenie");

        eq("nowa", Telegram.command("/nowa"), "komenda");
        eq("nowa", Telegram.command("/Nowa@MojBot"), "komenda z nazwą bota i wielką literą");
        eq("start", Telegram.command("/start coś jeszcze"), "komenda z argumentem");
        eq("pomoc", Telegram.command("  /pomoc  "), "spacje wokół");
        eq("a_b1", Telegram.command("/a_b1"), "podkreślenie i cyfra");
        eq("nowa", Telegram.command("/nowa@"), "sama małpa na końcu");
        eq(null, Telegram.command("/"), "sam ukośnik");
        eq(null, Telegram.command("//"), "dwa ukośniki");
        eq(null, Telegram.command("nowa"), "bez ukośnika");
        eq(null, Telegram.command("/etc/passwd co to?"), "ścieżka to pytanie, nie komenda");
        eq(null, Telegram.command("/ścieżka"), "polskie litery w nazwie");
        eq(null, Telegram.command(null), "null");
        eq(null, Telegram.command("Co to /nowa"), "komenda nie na początku");
    }

    static void formatTests() {
        List<String> parts = Arrays.asList("A.", "B.", "C.");
        eq(Arrays.asList("Część 1/3\nA.", "Część 2/3\nB.", "Część 3/3\nC."), Telegram.format(parts, "Część", true, false), "po kolei");
        eq(Arrays.asList("Część 3/3\nC.", "Część 2/3\nB.", "Część 1/3\nA."), Telegram.format(parts, "Część", true, true), "od ostatniej");
        eq(Arrays.asList("A."), Telegram.format(Arrays.asList("A."), "Gemini", false, false), "jedna część bez numeru");
        eq(Arrays.asList("Część 1/1\nA."), Telegram.format(Arrays.asList("A."), "Część", true, false), "jedna część z numerem");
        eq(Arrays.asList("Gemini 2/2\nB.", "Gemini 1/2\nA."), Telegram.format(Arrays.asList("A.", "B."), "Gemini", false, true), "Gemini od ostatniej");
        eq(0, Telegram.format(new ArrayList<>(), "Część", true, false).size(), "pusta lista");

        List<String> small = Arrays.asList("Krótka.", "Druga.");
        eq(small, Telegram.fit(small), "krótkie części bez zmian");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) sb.append("słowo ");
        String big = sb.toString().trim() + ".";
        ok(big.length() > Telegram.PART_LIMIT, "przykład jest dłuższy niż limit");
        List<String> fitted = Telegram.fit(Arrays.asList("Przed.", big, "Po."));
        ok(fitted.size() >= 4, "długa część podzielona: " + fitted.size());
        eq("Przed.", fitted.get(0), "pierwsza bez zmian");
        eq("Po.", fitted.get(fitted.size() - 1), "ostatnia bez zmian");
        for (String f : fitted) ok(f.length() <= Telegram.PART_LIMIT, "każda część mieści się: " + f.length());
        int words = 0;
        for (int i = 1; i < fitted.size() - 1; i++) words += fitted.get(i).split(" ").length;
        eq(700, words, "żadne słowo nie zginęło");
        // z numerem w pierwszej linii nadal mieści się w wiadomości
        for (String m : Telegram.format(fitted, "Część", true, false)) ok(m.length() <= Telegram.MAX_TEXT, "z numerem też");
    }

    static void destTests() {
        // flags(wybór, telegramGotowy, pytanieZTelegrama)
        for (boolean fromTg : new boolean[]{false, true}) {
            for (int c : new int[]{Dest.NOTIFICATIONS, Dest.TELEGRAM, Dest.BOTH, 7, -1}) {
                eq(Dest.F_NOTIFICATIONS, Dest.flags(c, false, fromTg), "bez Telegrama zawsze powiadomienia (" + c + ", " + fromTg + ")");
            }
        }
        eq(Dest.F_NOTIFICATIONS, Dest.flags(Dest.NOTIFICATIONS, true, false), "powiadomienia");
        eq(Dest.F_TELEGRAM, Dest.flags(Dest.TELEGRAM, true, false), "Telegram");
        eq(Dest.F_NOTIFICATIONS | Dest.F_TELEGRAM, Dest.flags(Dest.BOTH, true, false), "oba");
        eq(Dest.F_NOTIFICATIONS, Dest.flags(9, true, false), "nieznany wybór to powiadomienia");
        eq(Dest.F_TELEGRAM, Dest.flags(Dest.NOTIFICATIONS, true, true), "pytanie z Telegrama: odpowiedź wraca do Telegrama");
        eq(Dest.F_TELEGRAM, Dest.flags(Dest.TELEGRAM, true, true), "pytanie z Telegrama, wybór Telegram");
        eq(Dest.F_NOTIFICATIONS | Dest.F_TELEGRAM, Dest.flags(Dest.BOTH, true, true), "pytanie z Telegrama, wybór oba");
        eq(Dest.NOTIFICATIONS, Dest.clean(5), "clean: nieznany");
        eq(Dest.TELEGRAM, Dest.clean(Dest.TELEGRAM), "clean: Telegram");
    }

    static void parseTests() {
        Telegram.Reply r = Telegram.parseReply(200, "{\"ok\":true,\"result\":[1,2]}");
        ok(r.ok(), "200 ok");
        eq(2, Json.asArray(r.result).size(), "result");

        r = Telegram.parseReply(401, err(401, "Unauthorized"));
        contains(r.error, "odrzucił token", "401");
        eq(401, r.code, "kod 401");
        contains(Telegram.parseReply(404, err(404, "Not Found")).error, "nie zna takiego bota", "404");
        contains(Telegram.parseReply(409, err(409, "Conflict: terminated by other getUpdates request; make sure that only one bot instance is running")).error, "inny program", "409: drugi odbiorca");
        contains(Telegram.parseReply(409, err(409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first")).error, "webhook", "409: webhook");
        contains(Telegram.parseReply(400, err(400, "Bad Request: chat not found")).error, "nie zna tego czatu", "400: czat");
        contains(Telegram.parseReply(400, err(400, "Bad Request: message is empty")).error, "message is empty", "400: treść serwera");
        r = Telegram.parseReply(403, err(403, "Forbidden: bot was blocked by the user"));
        contains(r.error, "(403)", "403");
        contains(r.error, "blocked by the user", "403: treść serwera");
        r = Telegram.parseReply(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 5\",\"parameters\":{\"retry_after\":5}}");
        eq(429, r.code, "429: kod");
        eq(5, r.retryAfter, "429: retry_after");
        contains(r.error, "Poczekaj 5 s", "429: opis");
        eq(0, Telegram.parseReply(429, err(429, "Too Many Requests")).retryAfter, "429 bez parametrów");
        r = Telegram.parseReply(502, "<html>Bad gateway</html>");
        eq(502, r.code, "502 bez JSON-a: kod");
        contains(r.error, "(502)", "502");
        contains(r.error, "Bad gateway", "502: początek treści");
        contains(Telegram.parseReply(418, "").error, "błąd 418", "inny kod");
        r = Telegram.parseReply(200, "<html>Zaloguj się do Wi-Fi</html>");
        eq(0, r.code, "200 bez JSON-a: kod 0 (do ponowienia)");
        contains(r.error, "nie rozumiem", "200 bez JSON-a");
        contains(r.error, "Zaloguj się do Wi-Fi", "200 bez JSON-a: treść");
        ok(!Telegram.parseReply(200, "{\"ok\":false,\"error_code\":400,\"description\":\"x\"}").ok(), "200 z ok:false to błąd");
        eq(400, Telegram.parseReply(200, "{\"ok\":false,\"error_code\":400,\"description\":\"x\"}").code, "kod z error_code");
        ok(!Telegram.parseReply(200, "{}").ok(), "pusty obiekt to nie sukces");

        // aktualizacje
        String raw = "[" + upd(10, msg(1, OWNER, "private", false, "Cześć 😀", null))
                + "," + upd(11, msg(2, -1001234567890L, "supergroup", false, "w grupie", null))
                + "," + "{\"update_id\":12,\"edited_message\":{\"message_id\":1}}"
                + "," + upd(13, msg(3, OWNER, "private", false, null, null))
                + "," + upd(14, msg(4, OWNER, "private", false, "Dlaczego?", quoted("Bo tak.", true)))
                + "," + "{\"nie_ma_numeru\":1}" + ",5,"
                + upd(15, "{\"message_id\":9,\"chat\":{\"type\":\"private\"},\"text\":\"bez id czatu\"}")
                + "," + upd(16, msg(5, 55, "private", true, "od bota", null)) + "]";
        Telegram.Updates u = Telegram.parseUpdates(Json.parse(raw));
        ok(u.ok(), "parseUpdates");
        eq(7, u.list.size(), "siedem rozpoznanych aktualizacji");
        eq(16L, u.lastId, "największy numer");
        Telegram.Update a = u.list.get(0);
        eq(10L, a.id, "id");
        eq(OWNER, a.chatId, "czat");
        eq("private", a.chatType, "typ czatu");
        eq("Jan Kowalski", a.chatName, "imię i nazwisko");
        eq("Cześć 😀", a.text, "tekst");
        eq(false, a.fromBot, "nie od bota");
        eq(null, a.quoted, "bez cytatu");
        Telegram.Update g = u.list.get(1);
        eq(-1001234567890L, g.chatId, "duży ujemny numer czatu zachowany co do cyfry");
        eq("supergroup", g.chatType, "typ grupy");
        eq("Grupa testowa", g.chatName, "nazwa grupy");
        Telegram.Update edited = u.list.get(2);
        eq(12L, edited.id, "inna aktualizacja zachowuje numer");
        eq(0L, edited.chatId, "inna aktualizacja bez czatu");
        eq(null, edited.text, "inna aktualizacja bez tekstu");
        eq(null, u.list.get(3).text, "wiadomość bez tekstu (np. naklejka)");
        eq(OWNER, u.list.get(3).chatId, "wiadomość bez tekstu ma czat");
        Telegram.Update q = u.list.get(4);
        eq("Dlaczego?", q.text, "odpowiedź: tekst");
        eq("Bo tak.", q.quoted, "odpowiedź: cytowana wiadomość");
        eq(true, q.quotedFromBot, "cytowana od bota");
        eq(0L, u.list.get(5).chatId, "wiadomość bez numeru czatu jest pomijana");
        eq(true, u.list.get(6).fromBot, "od bota");

        ok(!Telegram.parseUpdates("nie lista").ok(), "result nie jest listą");
        eq(0L, Telegram.parseUpdates(new ArrayList<>()).lastId, "pusta lista");

        // nazwa czatu: imię, potem @nazwa, potem tytuł
        Telegram.Updates names = Telegram.parseUpdates(Json.parse("[{\"update_id\":1,\"message\":{\"chat\":{\"id\":1,\"type\":\"private\",\"username\":\"jan_k\"}}},"
                + "{\"update_id\":2,\"message\":{\"chat\":{\"id\":2,\"type\":\"private\",\"first_name\":\"Ola\"}}},"
                + "{\"update_id\":3,\"message\":{\"chat\":{\"id\":3,\"type\":\"private\"}}}]"));
        eq("@jan_k", names.list.get(0).chatName, "nazwa z username");
        eq("Ola", names.list.get(1).chatName, "samo imię");
        eq("", names.list.get(2).chatName, "bez nazwy");

        // liczby: tylko całkowite
        eq(null, Telegram.asLong(1.5), "ułamek");
        eq(null, Telegram.asLong("1"), "tekst");
        eq(null, Telegram.asLong(Double.NaN), "NaN");
        eq(null, Telegram.asLong(1e300), "zbyt duża");
        eq(-5L, Telegram.asLong(-5.0), "ujemna całkowita");
    }

    // ------------------------------------------------------------ serwer

    static void apiTests(String base) throws Exception {
        // getMe
        Telegram.Reply me = Telegram.getMe(base, TOKEN, 2000, 2000);
        ok(me.ok(), "getMe: " + me.error);
        eq("@dzielnik_test_bot", Telegram.botLabel(me), "nazwa bota");
        eq("/bot" + TOKEN + "/getMe", seen.get(0).path, "ścieżka z tokenem");
        eq("POST", seen.get(0).method, "metoda");
        contains(seen.get(0).contentType, "application/json", "typ treści");
        eq("bot", Telegram.botLabel(null), "botLabel(null)");
        eq("Dzielnik", Telegram.botLabel(Telegram.Reply.ok(200, Json.parse("{\"first_name\":\"Dzielnik\"}"))), "botLabel bez nazwy użytkownika");

        // zły token
        Telegram.Reply bad = Telegram.getMe(base, "111111:ZlyTokenZlyTokenZlyToken1234", 2000, 2000);
        ok(!bad.ok(), "zły token");
        eq(401, bad.code, "zły token: kod");
        contains(bad.error, "odrzucił token", "zły token: opis");

        // serwer odsyła token w opisie błędu: nie może trafić do komunikatu
        Telegram.Reply echo = Telegram.getMe(base, ECHO_TOKEN, 2000, 2000);
        ok(!echo.ok(), "echo");
        notContains(echo.error, ECHO_TOKEN, "token zakryty w błędzie");
        contains(echo.error, "***", "zamiast tokenu gwiazdki");
        contains(echo.error, "coś nie tak z", "reszta opisu zostaje");

        // token z niedozwolonymi znakami nie wychodzi z telefonu
        seen.clear();
        Telegram.Reply weird = Telegram.getMe(base, "12 3:abc def", 2000, 2000);
        ok(!weird.ok(), "token ze spacją");
        contains(weird.error, "niedozwolone znaki", "token ze spacją: opis");
        eq(401, weird.code, "token ze spacją: kod (pętla traktuje to jako błąd krytyczny)");

        // getUpdates: bez offsetu, z numerami, potwierdzanie przez offset
        reset();
        enqueue(10, msg(1, OWNER, "private", false, "Pierwsze.", null));
        enqueue(11, msg(2, OWNER, "private", false, "Drugie.", null));
        enqueue(12, msg(3, OWNER, "private", false, "Trzecie.", null));
        Telegram.Updates u = Telegram.getUpdates(base, TOKEN, 0, 0, 2000, 2000);
        ok(u.ok(), "getUpdates: " + u.error);
        eq(3, u.list.size(), "trzy wiadomości");
        eq(12L, u.lastId, "lastId");
        Map<String, Object> req = Json.asObject(Json.parse(seen.get(0).body));
        ok(!req.containsKey("offset"), "bez offsetu, gdy 0");
        eq(Arrays.asList("message"), Json.asArray(req.get("allowed_updates")), "tylko wiadomości");
        eq(0.0, req.get("timeout"), "timeout 0");
        eq(20.0, req.get("limit"), "limit");

        u = Telegram.getUpdates(base, TOKEN, 12, 0, 2000, 2000);
        eq(1, u.list.size(), "offset 12: serwer potwierdził 10 i 11, zostaje 12");
        eq(12L, u.list.get(0).id, "zostało id 12");
        req = Json.asObject(Json.parse(seen.get(1).body));
        eq(12.0, req.get("offset"), "offset w zapytaniu");

        long t0 = System.nanoTime();
        u = Telegram.getUpdates(base, TOKEN, 13, 30, 2000, 5000);
        long waited = (System.nanoTime() - t0) / 1_000_000;
        ok(u.ok() && u.list.isEmpty(), "offset 13: pusto");
        ok(waited >= 80, "długie odpytywanie czeka na wiadomość: " + waited + " ms");
        eq(30.0, Json.asObject(Json.parse(seen.get(2).body)).get("timeout"), "timeout 30 s w zapytaniu");

        // wiadomość, która przychodzi w trakcie czekania, kończy czekanie od razu
        Thread later = new Thread(() -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException ignored) {
                // koniec testu
            }
            enqueue(20, msg(9, OWNER, "private", false, "W trakcie czekania.", null));
        });
        later.start();
        u = Telegram.getUpdates(base, TOKEN, 13, 30, 2000, 5000);
        later.join();
        eq(1, u.list.size(), "wiadomość z czasu czekania");
        eq("W trakcie czekania.", u.list.get(0).text, "jej treść");

        // błędy w getUpdates
        overrides.add(new Scripted("getUpdates", 409, err(409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first"), 0));
        u = Telegram.getUpdates(base, TOKEN, 0, 0, 2000, 2000);
        ok(!u.ok(), "409");
        eq(409, u.code, "409: kod");
        contains(u.error, "webhook", "409: opis");
        overrides.add(new Scripted("getUpdates", 200, "{\"ok\":true,\"result\":\"coś innego\"}", 0));
        ok(!Telegram.getUpdates(base, TOKEN, 0, 0, 2000, 2000).ok(), "result nie jest listą");

        // brak odpowiedzi na czas
        overrides.add(new Scripted("getUpdates", 200, okEmpty(), 600));
        u = Telegram.getUpdates(base, TOKEN, 0, 0, 2000, 100);
        ok(!u.ok(), "przekroczony czas");
        eq(0, u.code, "przekroczony czas: kod 0");
        contains(u.error, "nie odpowiedział na czas", "przekroczony czas: opis");

        // sendMessage
        reset();
        Telegram.Reply sm = Telegram.sendMessage(base, TOKEN, OWNER, "Zażółć gęślą jaźń 😀.\nDruga linia \"w cudzysłowie\".", 2000, 2000);
        ok(sm.ok(), "sendMessage: " + sm.error);
        eq(1, sent.size(), "jedna wiadomość");
        eq("/bot" + TOKEN + "/sendMessage", sent.get(0).path, "ścieżka sendMessage");
        contains(sent.get(0).contentType, "application/json", "sendMessage: typ treści");
        eq(OWNER, sentChat(0), "chat_id jako liczba");
        eq("Zażółć gęślą jaźń 😀.\nDruga linia \"w cudzysłowie\".", sentText(0), "tekst bez zniekształceń");
        Map<String, Object> sb = Json.asObject(Json.parse(sent.get(0).body));
        eq(Boolean.TRUE, Json.asObject(sb.get("link_preview_options")).get("is_disabled"), "podgląd linków wyłączony");
        ok(!sb.containsKey("parse_mode"), "zwykły tekst, bez parse_mode");
        sm = Telegram.sendMessage(base, TOKEN, -1001234567890L, "x", 2000, 2000);
        eq(-1001234567890L, sentChat(1), "ujemny numer czatu");
        Telegram.sendMessage(base, TOKEN, OWNER, "a".repeat(5000), 2000, 2000);
        eq(4096, sentText(2).length(), "tekst ponad limit Telegrama jest przycinany");
    }

    static void sendAllTests(String base) throws Exception {
        reset();
        List<int[]> progress = new ArrayList<>();
        long t0 = System.nanoTime();
        String error = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList("Jeden.", "Dwa.", "Trzy."), 30,
                () -> false, (d, t) -> progress.add(new int[]{d, t}), FAST);
        long took = (System.nanoTime() - t0) / 1_000_000;
        eq(null, error, "sendAll: bez błędu");
        eq(3, sent.size(), "trzy wiadomości");
        eq("Jeden.", sentText(0), "kolejność 1");
        eq("Dwa.", sentText(1), "kolejność 2");
        eq("Trzy.", sentText(2), "kolejność 3");
        eq(3, progress.size(), "postęp po każdej wiadomości");
        eq(1, progress.get(0)[0], "postęp: pierwszy");
        eq(3, progress.get(2)[0], "postęp: ostatni");
        eq(3, progress.get(2)[1], "postęp: razem");
        ok(took >= 55, "przerwy między wiadomościami (bez przerwy po ostatniej): " + took + " ms");

        // limit zapytań: Telegram prosi o zwolnienie, czekam i ponawiam, wiadomość dochodzi raz
        reset();
        t0 = System.nanoTime();
        error = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList("Pierwsza.", "[[429]] druga."), 5, () -> false, (d, t) -> { }, FAST);
        took = (System.nanoTime() - t0) / 1_000_000;
        eq(null, error, "429: bez błędu po ponowieniu");
        eq(2, sent.size(), "429: obie wiadomości dotarły");
        eq("[[429]] druga.", sentText(1), "429: druga wiadomość");
        eq(2, attempts.get("[[429]] druga.").get(), "429: dwie próby");
        ok(took >= 900, "429: czekałem retry_after (1 s): " + took + " ms");

        // chwilowa awaria serwera: ponowienie po krótkiej przerwie
        reset();
        error = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList("[[500]] awaria."), 5, () -> false, (d, t) -> { }, FAST);
        eq(null, error, "500: bez błędu po ponowieniu");
        eq(1, sent.size(), "500: wiadomość dotarła raz");
        eq(2, attempts.get("[[500]] awaria.").get(), "500: dwie próby");

        // czat zablokowany: błąd od razu, kolejne wiadomości nie idą
        reset();
        error = Telegram.sendAll(base, TOKEN, 403403, Arrays.asList("Pierwsza.", "Druga."), 5, () -> false, (d, t) -> { }, FAST);
        ok(error != null, "403: jest błąd");
        contains(error, "(403)", "403: kod");
        contains(error, "blocked by the user", "403: treść serwera");
        eq(1, count("sendMessage"), "403: tylko jedno zapytanie, bez ponawiania");
        eq(0, sent.size(), "403: nic nie dotarło");

        reset();
        error = Telegram.sendAll(base, TOKEN, 404404, Arrays.asList("Pierwsza."), 5, () -> false, (d, t) -> { }, FAST);
        contains(error, "nie zna tego czatu", "400: czat nie istnieje");
        eq(1, count("sendMessage"), "400: bez ponawiania");

        // zły token: bez ponawiania
        reset();
        error = Telegram.sendAll(base, "111111:ZlyTokenZlyTokenZlyToken1234", OWNER, Arrays.asList("A.", "B."), 5, () -> false, (d, t) -> { }, FAST);
        contains(error, "odrzucił token", "zły token");
        eq(1, count("sendMessage"), "zły token: jedno zapytanie");

        // zatrzymanie przez użytkownika po pierwszej wiadomości
        reset();
        AtomicInteger done = new AtomicInteger();
        error = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList("A.", "B.", "C."), 5, () -> done.get() >= 1, (d, t) -> done.set(d), FAST);
        eq(null, error, "stop: to nie błąd");
        eq(1, sent.size(), "stop: poszła tylko pierwsza");

        // bardzo długa wiadomość jest dzielona na kilka
        reset();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4500; i++) sb.append("ab ");
        String big = sb.toString().trim();
        error = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList(big), 5, () -> false, (d, t) -> { }, FAST);
        eq(null, error, "długa: bez błędu");
        ok(sent.size() >= 4, "długa: podzielona na " + sent.size());
        int words = 0;
        for (int i = 0; i < sent.size(); i++) {
            ok(sentText(i).length() <= Telegram.MAX_TEXT, "długa: część " + i + " mieści się w limicie");
            words += sentText(i).split(" ").length;
        }
        eq(4500, words, "długa: wszystkie słowa dotarły");

        // pusta lista: nic się nie dzieje
        reset();
        eq(null, Telegram.sendAll(base, TOKEN, OWNER, new ArrayList<>(), 5, () -> false, (d, t) -> { }, FAST), "pusta lista");
        eq(0, seen.size(), "pusta lista: żadnego zapytania");
    }

    static void linkTests(String base) {
        // nikt jeszcze nie napisał do bota
        reset();
        Telegram.Link l = Telegram.link(base, TOKEN, 2000, 2000);
        ok(!l.linked(), "brak czatu");
        eq(null, l.error, "brak czatu: to nie błąd");
        eq("@dzielnik_test_bot", l.bot, "brak czatu: nazwa bota znana");
        eq(0, sent.size(), "brak czatu: bez powitania");

        // grupa, dwa prywatne czaty i bot: wybieram ostatni prywatny od człowieka
        reset();
        enqueue(30, msg(1, -100777, "group", false, "w grupie", null));
        enqueue(31, msg(2, 5151, "private", false, "/start", null));
        enqueue(32, msg(3, OWNER, "private", false, "/start", null));
        enqueue(33, msg(4, 6262, "private", true, "inny bot", null));
        queue.put(34L, "{\"update_id\":34,\"edited_message\":{\"message_id\":1}}");
        l = Telegram.link(base, TOKEN, 2000, 2000);
        ok(l.linked(), "połączono: " + l.error);
        eq(OWNER, l.chatId, "wybrany czat");
        eq("Jan Kowalski", l.chatName, "nazwa czatu");
        eq(35L, l.nextOffset, "nasłuch od kolejnej aktualizacji");
        eq(1, sent.size(), "powitanie wysłane");
        eq(OWNER, sentChat(0), "powitanie do właściwego czatu");
        eq(Telegram.GREETING, sentText(0), "treść powitania");

        // zły token
        reset();
        l = Telegram.link(base, "111111:ZlyTokenZlyTokenZlyToken1234", 2000, 2000);
        ok(!l.linked(), "zły token");
        contains(l.error, "odrzucił token", "zły token: opis");

        // nie da się napisać do czatu
        reset();
        enqueue(40, msg(1, 403403, "private", false, "/start", null));
        l = Telegram.link(base, TOKEN, 2000, 2000);
        ok(!l.linked(), "zablokowany bot");
        contains(l.error, "(403)", "zablokowany bot: opis");

        // webhook
        reset();
        overrides.add(new Scripted("getUpdates", 409, err(409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first"), 0));
        l = Telegram.link(base, TOKEN, 2000, 2000);
        ok(!l.linked(), "webhook");
        contains(l.error, "webhook", "webhook: opis");
        eq("@dzielnik_test_bot", l.bot, "webhook: nazwa bota znana");
    }

    // ------------------------------------------------------------ pętla nasłuchu

    static final class TestHost implements TelegramBridge.Host {
        volatile boolean stop;
        volatile long offset;
        final long stopAfterOffset;
        final List<Telegram.Update> messages = new CopyOnWriteArrayList<>();
        final List<Long> saved = new CopyOnWriteArrayList<>();
        final List<String> problems = new CopyOnWriteArrayList<>();
        final List<String> fatals = new CopyOnWriteArrayList<>();
        volatile long throwOnId = -1;

        TestHost(long startOffset, long stopAfterOffset) {
            this.offset = startOffset;
            this.stopAfterOffset = stopAfterOffset;
        }

        @Override
        public boolean stopped() {
            return stop;
        }

        @Override
        public long nextOffset() {
            return offset;
        }

        @Override
        public void saveOffset(long next) {
            offset = next;
            saved.add(next);
            if (stopAfterOffset > 0 && next >= stopAfterOffset) stop = true;
        }

        @Override
        public void message(Telegram.Update u) {
            messages.add(u);
            if (u.id == throwOnId) throw new IllegalStateException("celowy błąd");
        }

        @Override
        public void problem(String text) {
            problems.add(text == null ? "(ok)" : text);
        }

        @Override
        public void fatal(String text) {
            fatals.add(text);
        }
    }

    static void run(String base, String token, TestHost host, int idleGuardMs) throws Exception {
        TelegramBridge.run(base, token, OWNER, host, 30, new int[]{5, 10}, idleGuardMs);
    }

    static void bridgeTests(String base) throws Exception {
        // prawo do obsługi: tylko prywatny czat właściciela, nie od bota
        Telegram.Update own = new Telegram.Update(1, OWNER, "private", "Jan", false, "x", null, false);
        ok(TelegramBridge.accepts(own, OWNER), "właściciel");
        ok(!TelegramBridge.accepts(new Telegram.Update(1, 99, "private", "", false, "x", null, false), OWNER), "obcy czat");
        ok(!TelegramBridge.accepts(new Telegram.Update(1, OWNER, "group", "", false, "x", null, false), OWNER), "grupa o tym samym numerze");
        ok(!TelegramBridge.accepts(new Telegram.Update(1, OWNER, "private", "", true, "x", null, false), OWNER), "od bota");
        ok(!TelegramBridge.accepts(new Telegram.Update(1, 0, "", "", false, null, null, false), 0), "inna aktualizacja bez czatu");
        ok(TelegramBridge.accepts(new Telegram.Update(1, OWNER, "private", "", false, null, null, false), OWNER), "wiadomość bez tekstu też trafia do obsługi");

        // zwykły obieg: obcy i grupa są pomijane, ale ich numery się potwierdzają
        reset();
        enqueue(10, msg(1, OWNER, "private", false, "Pytanie 1.", null));
        enqueue(11, msg(2, 99, "private", false, "intruz", null));
        enqueue(12, msg(3, -100500, "group", false, "w grupie", null));
        queue.put(13L, "{\"update_id\":13,\"edited_message\":{\"message_id\":1}}");
        enqueue(14, msg(4, OWNER, "private", false, null, null));
        enqueue(15, msg(5, OWNER, "private", false, "/nowa", null));
        enqueue(16, msg(6, OWNER, "private", false, "Dlaczego?", quoted("Bo tak.", true)));
        TestHost h = new TestHost(0, 17);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(10L, 14L, 15L, 16L), ids(h.messages), "obsłużone tylko wiadomości właściciela");
        eq(Arrays.asList(11L, 12L, 13L, 14L, 15L, 16L, 17L), h.saved, "numer zapisany po każdej aktualizacji, także po pominiętych");
        eq("Pytanie 1.", h.messages.get(0).text, "treść pytania");
        eq(null, h.messages.get(1).text, "wiadomość bez tekstu trafia do obsługi");
        eq("/nowa", h.messages.get(2).text, "komenda trafia do obsługi");
        eq("Bo tak.", h.messages.get(3).quoted, "cytat przekazany");
        ok(h.problems.isEmpty(), "bez problemów: " + h.problems);
        eq(0, h.fatals.size(), "bez błędów krytycznych");

        // ponowne uruchomienie: zapisany numer sprawia, że stare wiadomości nie wracają
        enqueue(20, msg(7, OWNER, "private", false, "Po restarcie.", null));
        seen.clear();
        h = new TestHost(17, 21);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(20L), ids(h.messages), "po restarcie tylko nowa wiadomość");
        eq(17.0, Json.asObject(Json.parse(seen.get(0).body)).get("offset"), "pierwsze zapytanie z zapisanym numerem");
        synchronized (queue) {
            ok(!queue.containsKey(10L) && !queue.containsKey(16L), "serwer potwierdził stare wiadomości");
        }

        // wiadomości z czasu przerwy są obsłużone po starcie (nic nie ginie)
        reset();
        enqueue(50, msg(1, OWNER, "private", false, "Z przerwy 1.", null));
        enqueue(51, msg(2, OWNER, "private", false, "Z przerwy 2.", null));
        h = new TestHost(50, 52);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(50L, 51L), ids(h.messages), "wiadomości z przerwy");

        // chwilowe awarie: ponawianie z przerwą, potem powrót do normy
        reset();
        enqueue(60, msg(1, OWNER, "private", false, "Po awarii.", null));
        overrides.add(new Scripted("getUpdates", 500, "<html>Internal Server Error</html>", 0));
        overrides.add(new Scripted("getUpdates", 502, "<html>Bad gateway</html>", 0));
        h = new TestHost(0, 61);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(60L), ids(h.messages), "po awarii wiadomość jednak dotarła");
        eq(3, h.problems.size(), "dwa problemy i powrót do normy: " + h.problems);
        contains(h.problems.get(0), "(500)", "pierwszy problem");
        contains(h.problems.get(1), "(502)", "drugi problem");
        eq("(ok)", h.problems.get(2), "powrót do normy");
        eq(0, h.fatals.size(), "awaria serwera nie jest błędem krytycznym");

        // limit zapytań (429): czekam tyle, ile prosi Telegram
        reset();
        overrides.add(new Scripted("getUpdates", 429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 1\",\"parameters\":{\"retry_after\":1}}", 0));
        enqueue(70, msg(1, OWNER, "private", false, "Po limicie.", null));
        h = new TestHost(0, 71);
        long t0 = System.nanoTime();
        run(base, TOKEN, h, 5);
        long took = (System.nanoTime() - t0) / 1_000_000;
        ok(took >= 900, "429: czekam retry_after: " + took + " ms");
        eq(Arrays.asList(70L), ids(h.messages), "429: wiadomość dotarła");
        contains(h.problems.get(0), "(429)", "429: zgłoszone");

        // konflikt (drugi odbiorca): ponawiam, nie kończę
        reset();
        overrides.add(new Scripted("getUpdates", 409, err(409, "Conflict: terminated by other getUpdates request; make sure that only one bot instance is running"), 0));
        enqueue(80, msg(1, OWNER, "private", false, "Po konflikcie.", null));
        h = new TestHost(0, 81);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(80L), ids(h.messages), "409: po konflikcie dalej działam");
        contains(h.problems.get(0), "(409)", "409: zgłoszone");

        // zły token: błąd krytyczny, pętla kończy się od razu
        reset();
        h = new TestHost(0, 0);
        t0 = System.nanoTime();
        run(base, "111111:ZlyTokenZlyTokenZlyToken1234", h, 5);
        took = (System.nanoTime() - t0) / 1_000_000;
        eq(1, h.fatals.size(), "zły token: błąd krytyczny");
        contains(h.fatals.get(0), "odrzucił token", "zły token: opis");
        eq(1, count("getUpdates"), "zły token: jedno zapytanie");
        ok(took < 1500, "zły token: pętla kończy się od razu: " + took + " ms");

        // wyjątek przy obsłudze jednej wiadomości nie zatrzymuje nasłuchu ani nie powtarza wiadomości
        reset();
        enqueue(90, msg(1, OWNER, "private", false, "Wywołam błąd.", null));
        enqueue(91, msg(2, OWNER, "private", false, "Dalej działa.", null));
        h = new TestHost(0, 92);
        h.throwOnId = 90;
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(90L, 91L), ids(h.messages), "po wyjątku obsłużona następna");
        eq(Arrays.asList(91L, 92L), h.saved, "numer zapisany także po wyjątku");
        contains(h.problems.get(0), "celowy błąd", "wyjątek zgłoszony");

        // serwer odpowiadający od razu pustą listą nie rozkręca pętli bez przerwy
        reset();
        for (int i = 0; i < 4; i++) overrides.add(new Scripted("getUpdates", 200, okEmpty(), 0));
        final TestHost guard = new TestHost(0, 0);
        Thread stopper = new Thread(() -> {
            long end = System.currentTimeMillis() + 5000;
            while (count("getUpdates") < 4 && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            guard.stop = true;
        });
        stopper.start();
        t0 = System.nanoTime();
        run(base, TOKEN, guard, 60);
        took = (System.nanoTime() - t0) / 1_000_000;
        stopper.join();
        ok(took >= 3 * 60, "puste szybkie odpowiedzi są spowalniane: " + took + " ms");

        // zatrzymanie w czasie długiego odpytywania: pętla kończy się szybko
        reset();
        TestHost idle = new TestHost(0, 0);
        Thread runner = new Thread(() -> {
            try {
                run(base, TOKEN, idle, 5);
            } catch (Exception e) {
                idle.problems.add("wyjątek: " + e);
            }
        });
        runner.start();
        Thread.sleep(60);
        idle.stop = true;
        t0 = System.nanoTime();
        runner.join(3000);
        took = (System.nanoTime() - t0) / 1_000_000;
        ok(!runner.isAlive(), "pętla zatrzymana");
        ok(took < 1500, "zatrzymanie trwa krócej niż długie odpytywanie: " + took + " ms");
        ok(idle.messages.isEmpty() && idle.fatals.isEmpty(), "zatrzymanie nie jest błędem");

        // zatrzymanie w środku listy wiadomości: reszta zostaje na serwerze i nie jest potwierdzona
        reset();
        enqueue(100, msg(1, OWNER, "private", false, "Jeden.", null));
        enqueue(101, msg(2, OWNER, "private", false, "Dwa.", null));
        enqueue(102, msg(3, OWNER, "private", false, "Trzy.", null));
        h = new TestHost(0, 101);
        run(base, TOKEN, h, 5);
        eq(Arrays.asList(100L), ids(h.messages), "zatrzymanie po pierwszej wiadomości");
        eq(Arrays.asList(101L), h.saved, "zapisany tylko numer po pierwszej");
        eq(101L, h.offset, "następne uruchomienie zacznie od drugiej");
    }

    static List<Long> ids(List<Telegram.Update> list) {
        List<Long> out = new ArrayList<>();
        for (Telegram.Update u : list) out.add(u.id);
        return out;
    }

    static void closedPortTests() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = ss.getLocalPort();
        }
        String base = "http://127.0.0.1:" + port;
        Telegram.Reply r = Telegram.getMe(base, TOKEN, 500, 500);
        ok(!r.ok(), "zamknięty port");
        contains(r.error, "Nie udało się połączyć z Telegramem", "zamknięty port: opis");
        notContains(r.error, TOKEN, "zamknięty port: bez tokenu");
        eq(0, r.code, "zamknięty port: kod 0");
        String err = Telegram.sendAll(base, TOKEN, OWNER, Arrays.asList("A."), 5, () -> false, (d, t) -> { }, FAST);
        contains(err, "Nie udało się połączyć z Telegramem", "sendAll: zamknięty port");

        // pętla na zamkniętym porcie: raz po raz zgłasza problem i nie kończy się sama
        TestHost h = new TestHost(0, 0);
        Thread runner = new Thread(() -> {
            try {
                TelegramBridge.run(base, TOKEN, OWNER, h, 30, new int[]{5, 10}, 5);
            } catch (Exception ignored) {
                // przerwano
            }
        });
        runner.start();
        long end = System.currentTimeMillis() + 3000;
        while (h.problems.size() < 4 && System.currentTimeMillis() < end) Thread.sleep(5);
        h.stop = true;
        runner.interrupt();
        runner.join(2000);
        ok(h.problems.size() >= 4, "pętla ponawia mimo braku sieci: " + h.problems.size());
        ok(!runner.isAlive(), "pętla kończy się po zatrzymaniu");
        eq(0, h.fatals.size(), "brak sieci nie jest błędem krytycznym");
    }

    public static void main(String[] args) throws Exception {
        tokenAndCommandTests();
        formatTests();
        destTests();
        parseTests();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", TelegramTest::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sztuczny-telegram");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            apiTests(base);
            sendAllTests(base);
            linkTests(base);
            bridgeTests(base);
        } finally {
            server.stop(0);
        }
        closedPortTests();
        System.out.println("OK: " + checks + " sprawdzeń");
    }
}
