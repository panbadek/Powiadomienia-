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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test części Gemini bez Androida: JSON, budowa zapytania, odczyt odpowiedzi i błędów,
 * ponawianie, pamięć rozmowy. Zapytania idą na sztuczny serwer HTTP z JDK.
 *
 * javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/{Json,Gemini,History}.java tests/pl/dzielnik/GeminiTest.java
 * java -cp out pl.dzielnik.GeminiTest
 */
public class GeminiTest {

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

    static void throwsParse(String doc) {
        checks++;
        try {
            Json.parse(doc);
        } catch (Json.ParseException e) {
            return;
        }
        throw new AssertionError("powinien być błąd parsowania: " + doc);
    }

    // ---------------------------------------------------------------- JSON

    static String stringify(Object o, Random rnd, boolean asciiOnly) {
        if (o == null) return "null";
        if (o instanceof Boolean) return o.toString();
        if (o instanceof Double) {
            double d = (Double) o;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : Double.toString(d);
        }
        if (o instanceof String) return asciiOnly ? escapeAll((String) o) : Json.quote((String) o);
        String sp = rnd.nextBoolean() ? "" : " \n\t";
        StringBuilder b = new StringBuilder();
        if (o instanceof List) {
            b.append('[').append(sp);
            boolean first = true;
            for (Object e : (List<?>) o) {
                if (!first) b.append(',').append(sp);
                first = false;
                b.append(stringify(e, rnd, asciiOnly));
            }
            return b.append(sp).append(']').toString();
        }
        b.append('{').append(sp);
        boolean first = true;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            if (!first) b.append(',').append(sp);
            first = false;
            b.append(stringify(e.getKey(), rnd, asciiOnly)).append(sp).append(':').append(sp)
                    .append(stringify(e.getValue(), rnd, asciiOnly));
        }
        return b.append(sp).append('}').toString();
    }

    /** Każdy znak jako \\uXXXX, tak jak robi Python z ensure_ascii. */
    static String escapeAll(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) b.append(String.format("\\u%04x", (int) s.charAt(i)));
        return b.append('"').toString();
    }

    static final String[] TRICKY = {
            "\"", "\\", "/", "\n", "\r", "\t", "\b", "\f", "\u0000", "\u001f", "a", "Z", "0", " ", "ą", "ę", "ł", "Ż",
            "€", "\u2028", "\u2029", "\uFEFF", "😀", "𝄞", "{", "}", "[", "]", ",", ":", "null", "true", "'"
    };

    static String randomString(Random rnd) {
        StringBuilder b = new StringBuilder();
        int n = rnd.nextInt(12);
        for (int i = 0; i < n; i++) b.append(TRICKY[rnd.nextInt(TRICKY.length)]);
        return b.toString();
    }

    static Object randomValue(Random rnd, int depth) {
        int k = rnd.nextInt(depth > 3 ? 5 : 7);
        switch (k) {
            case 0:
                return null;
            case 1:
                return rnd.nextBoolean();
            case 2:
                return (double) (rnd.nextInt(2001) - 1000);
            case 3:
                return rnd.nextInt(1000) / 8.0 - 20;
            case 4:
                return randomString(rnd);
            case 5: {
                List<Object> a = new ArrayList<>();
                int n = rnd.nextInt(5);
                for (int i = 0; i < n; i++) a.add(randomValue(rnd, depth + 1));
                return a;
            }
            default: {
                Map<String, Object> m = new LinkedHashMap<>();
                int n = rnd.nextInt(5);
                for (int i = 0; i < n; i++) m.put(randomString(rnd), randomValue(rnd, depth + 1));
                return m;
            }
        }
    }

    static void jsonTests() {
        eq(null, Json.parse("null"), "null");
        eq(Boolean.TRUE, Json.parse(" true "), "true");
        eq(Boolean.FALSE, Json.parse("false"), "false");
        eq(-0.0, Json.parse("-0"), "-0");
        eq(1000.0, Json.parse("1e3"), "1e3");
        eq(0.015, Json.parse("1.5E-2"), "1.5E-2");
        eq(12.0, Json.parse("12"), "12");
        eq("zażółć gęślą jaźń", Json.parse("\"zażółć gęślą jaźń\""), "polskie litery");
        eq("ą", Json.parse("\"\\u0105\""), "\\u0105");
        eq("😀", Json.parse("\"\\ud83d\\ude00\""), "para zastępcza z \\u");
        eq("a\"b\\c/d\be\ff\ng\rh\ti", Json.parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\""), "wszystkie znaki po backslashu");
        eq(new ArrayList<>(), Json.parse("[ ]"), "pusta tablica");
        eq(new LinkedHashMap<>(), Json.parse("{ }"), "pusty obiekt");
        eq(Arrays.asList(1.0, "x", null, true), Json.parse("[1,\"x\",null,true]"), "tablica mieszana");
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("a", Arrays.asList(1.0, 2.0));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", "c");
        exp.put("o", inner);
        eq(exp, Json.parse("{\"a\":[1,2],\"o\":{\"b\":\"c\"}}"), "zagnieżdżenie");
        eq(Arrays.asList("a", "b"), new ArrayList<>(Json.asObject(Json.parse("{\"a\":1,\"b\":2}")).keySet()), "kolejność pól");

        for (String bad : new String[]{"", "{", "[", "[1,]", "{\"a\":}", "{\"a\" 1}", "{a:1}", "\"abc", "tru", "nul", "-", "1.",
                "1e", "1e+", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "{} x", "[1 2]", "{\"a\":1,}", "'a'", "+1", ".5"}) {
            throwsParse(bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append('[');
        throwsParse(deep.toString());

        // quote: wyniki znane z góry
        eq("\"\"", Json.quote(""), "pusty tekst");
        eq("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\"", Json.quote("a\"b\\c\n\r\t\b\f"), "znaki specjalne");
        eq("\"\\u0000\\u001f\"", Json.quote("\u0000\u001f"), "znaki sterujące");
        eq("\"ą😀\"", Json.quote("ą😀"), "polskie litery i para zastępcza zostają");
        eq("\"\uFFFD\"", Json.quote("\uD83D"), "samotna górna połówka");
        eq("\"\uFFFDx\"", Json.quote("\uDE00x"), "samotna dolna połówka");
        eq("\"\uFFFD\uFFFD\"", Json.quote("\uD83D\uD83D"), "dwie górne połówki");

        // losowo: zapis i odczyt dają to samo, także gdy każdy znak jest zapisany jako \\uXXXX
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            Object v = randomValue(rnd, 0);
            eq(v, Json.parse(stringify(v, rnd, false)), "zapis przez quote, próba " + i);
            eq(v, Json.parse(stringify(v, rnd, true)), "zapis przez \\u, próba " + i);
        }
        for (int i = 0; i < 20000; i++) {
            String s = randomString(rnd);
            eq(s, Json.parse(Json.quote(s)), "quote/parse: " + s);
        }
    }

    // ------------------------------------------------- zapytanie i odpowiedź

    static String text(String... parts) {
        StringBuilder b = new StringBuilder("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append(',');
            b.append("{\"text\":").append(Json.quote(parts[i])).append('}');
        }
        return b.append("]},\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":5}}").toString();
    }

    static void requestTests() {
        List<Gemini.Turn> hist = Arrays.asList(new Gemini.Turn("user", "Cześć \"Gemini\""), new Gemini.Turn("model", "Witaj.\nW czym pomóc?"));
        Map<String, Object> req = Json.asObject(Json.parse(Gemini.buildRequest("Bądź zwięzły.", hist, "Co to jest 😀?")));
        Map<String, Object> sys = Json.asObject(req.get("systemInstruction"));
        eq("Bądź zwięzły.", Json.asString(Json.asObject(Json.asArray(sys.get("parts")).get(0)).get("text")), "systemInstruction");
        List<Object> contents = Json.asArray(req.get("contents"));
        eq(3, contents.size(), "liczba wypowiedzi");
        String[] roles = {"user", "model", "user"};
        String[] texts = {"Cześć \"Gemini\"", "Witaj.\nW czym pomóc?", "Co to jest 😀?"};
        for (int i = 0; i < 3; i++) {
            Map<String, Object> c = Json.asObject(contents.get(i));
            eq(roles[i], c.get("role"), "rola " + i);
            eq(texts[i], Json.asString(Json.asObject(Json.asArray(c.get("parts")).get(0)).get("text")), "tekst " + i);
        }
        Map<String, Object> noSys = Json.asObject(Json.parse(Gemini.buildRequest("", new ArrayList<>(), "x")));
        ok(!noSys.containsKey("systemInstruction"), "bez polecenia systemowego pole nie istnieje");
        eq(1, Json.asArray(noSys.get("contents")).size(), "sama wiadomość");
        // dziwna rola z zapisu nie przejdzie do zapytania
        Map<String, Object> odd = Json.asObject(Json.parse(Gemini.buildRequest(null, Arrays.asList(new Gemini.Turn("x\"y", "t")), "q")));
        eq("user", Json.asObject(Json.asArray(odd.get("contents")).get(0)).get("role"), "nieznana rola zamieniona na user");

        eq("gemini-3.5-flash-lite", Gemini.cleanModel(null), "model: null");
        eq("gemini-3.5-flash-lite", Gemini.cleanModel("  "), "model: pusty");
        eq("gemini-3.8-flash", Gemini.cleanModel(" models/gemini-3.8-flash "), "model: przedrostek models/");
        eq("gemini-3.5-flash-lite", Gemini.cleanModel("a/b"), "model: ukośnik");
        eq("gemini-3.5-flash-lite", Gemini.cleanModel("a b"), "model: spacja");
        eq("gemini-3.5-flash-lite", Gemini.cleanModel("m?x=1"), "model: znaki zapytania");
        eq("gemini-flash-latest", Gemini.cleanModel("gemini-flash-latest"), "model: alias");

        eq("Tak.", Gemini.compose(null, " Tak. "), "bez powiadomienia");
        eq("Tak.", Gemini.compose("  ", "Tak."), "puste powiadomienie");
        contains(Gemini.compose("Ala ma kota.", "Dlaczego?"), "Ala ma kota.", "kontekst w treści");
        contains(Gemini.compose("Ala ma kota.", "Dlaczego?"), "Moja odpowiedź:\nDlaczego?", "odpowiedź w treści");
    }

    static void parseTests() {
        Gemini.Result r = Gemini.parseResponse(200, text("Pierwsza część. ", "Druga część.\n"));
        ok(r.ok(), "200 z tekstem");
        eq("Pierwsza część. Druga część.", r.text, "części sklejone i przycięte");

        r = Gemini.parseResponse(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"myśli\",\"thought\":true},{\"text\":\"Wynik.\"}]}}]}");
        eq("Wynik.", r.text, "części z myślami pomijam");

        r = Gemini.parseResponse(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Ucięte\"}]},\"finishReason\":\"MAX_TOKENS\"}]}");
        ok(r.ok() && r.text.equals("Ucięte"), "MAX_TOKENS z tekstem jest odpowiedzią");

        r = Gemini.parseResponse(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        ok(!r.ok(), "zablokowane pytanie");
        contains(r.error, "SAFETY", "powód blokady");

        r = Gemini.parseResponse(200, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
        ok(!r.ok(), "bez tekstu i z powodem");
        contains(r.error, "SAFETY", "powód zakończenia");

        r = Gemini.parseResponse(200, "{\"candidates\":[]}");
        contains(r.error, "pustą", "puste kandydaty");
        r = Gemini.parseResponse(200, "{}");
        contains(r.error, "pustą", "pusty obiekt");
        r = Gemini.parseResponse(200, "<html>portal logowania</html>");
        contains(r.error, "nie rozumiem", "to nie JSON");
        contains(r.error, "portal logowania", "początek treści w błędzie");
        r = Gemini.parseResponse(200, "[1,2]");
        ok(!r.ok(), "tablica zamiast obiektu");

        String keyBad = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}";
        contains(Gemini.parseResponse(400, keyBad).error, "Klucz API Gemini jest nieprawidłowy", "zły klucz");
        contains(Gemini.parseResponse(400, "{\"error\":{\"message\":\"Invalid JSON payload\"}}").error, "Invalid JSON payload", "inny błąd 400");
        contains(Gemini.parseResponse(403, "{\"error\":{\"message\":\"Permission denied\"}}").error, "odmówiło dostępu (403)", "403");
        contains(Gemini.parseResponse(404, "{\"error\":{\"message\":\"models/x is not found\"}}").error, "Nie znaleziono modelu", "404");
        contains(Gemini.parseResponse(404, "{\"error\":{\"message\":\"models/x is not found\"}}").error, "models/x is not found", "404: treść serwera");
        contains(Gemini.parseResponse(429, "{\"error\":{\"message\":\"Quota exceeded\"}}").error, "Limit zapytań", "429");
        contains(Gemini.parseResponse(503, "{\"error\":{\"message\":\"The model is overloaded\"}}").error, "przeciążony (503)", "503");
        contains(Gemini.parseResponse(502, "<html>Bad gateway</html>").error, "(502)", "502 bez JSON-a");
        contains(Gemini.parseResponse(502, "<html>Bad gateway</html>").error, "Bad gateway", "502: początek treści");
        contains(Gemini.parseResponse(418, "").error, "błąd 418", "inny kod");
        eq(400, Gemini.parseResponse(400, keyBad).status, "kod HTTP w wyniku");

        for (int s : new int[]{0, 429, 500, 502, 503, 504}) ok(Gemini.retryable(s), "ponawiam " + s);
        for (int s : new int[]{200, 400, 401, 403, 404, 418}) ok(!Gemini.retryable(s), "nie ponawiam " + s);
    }

    // ------------------------------------------------------ sztuczny serwer

    static final class Seen {
        final String method, path, key, contentType;
        final String body;

        Seen(String method, String path, String key, String contentType, String body) {
            this.method = method;
            this.path = path;
            this.key = key;
            this.contentType = contentType;
            this.body = body;
        }
    }

    static final List<Seen> seen = new CopyOnWriteArrayList<>();
    static final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    static final String KEY = "AIzaTestKey-123_abc";

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        try (InputStream is = ex.getRequestBody()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) in.write(buf, 0, n);
        }
        String path = ex.getRequestURI().getPath();
        seen.add(new Seen(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("x-goog-api-key"),
                ex.getRequestHeaders().getFirst("Content-Type"), new String(in.toByteArray(), StandardCharsets.UTF_8)));
        String scenario = path.substring(path.lastIndexOf('/') + 1).replace(":generateContent", "");
        int n = counts.computeIfAbsent(scenario, k -> new AtomicInteger()).incrementAndGet();
        switch (scenario) {
            case "ok":
                reply(ex, 200, text("Dzień dobry! ", "Zażółć gęślą jaźń 😀."));
                break;
            case "retry503":
                if (n == 1) reply(ex, 503, "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}");
                else reply(ex, 200, text("Po ponowieniu."));
                break;
            case "limit429":
                reply(ex, 429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric\",\"status\":\"RESOURCE_EXHAUSTED\"}}");
                break;
            case "badkey":
                reply(ex, 400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}");
                break;
            case "echo":
                reply(ex, 403, "{\"error\":{\"code\":403,\"message\":\"Key " + KEY + " is blocked\"}}");
                break;
            case "html":
                reply(ex, 200, "<html>Zaloguj się do sieci Wi-Fi</html>");
                break;
            case "slow":
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                    // koniec testu
                }
                reply(ex, 200, text("Za późno."));
                break;
            default:
                reply(ex, 404, "{\"error\":{\"code\":404,\"message\":\"models/" + scenario + " is not found for API version v1beta\",\"status\":\"NOT_FOUND\"}}");
        }
    }

    static void serverTests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", GeminiTest::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        int[] fast = {5, 5};
        List<Gemini.Turn> hist = Arrays.asList(new Gemini.Turn("user", "Pierwsze pytanie."), new Gemini.Turn("model", "Pierwsza odpowiedź."));
        try {
            // zwykła odpowiedź
            Gemini.Result r = Gemini.ask(base, "  " + KEY + " ", "ok", "System.", hist, "Drugie pytanie?", 2000, 2000, fast);
            ok(r.ok(), "ok: " + r.error);
            eq("Dzień dobry! Zażółć gęślą jaźń 😀.", r.text, "ok: treść");
            eq(1, seen.size(), "ok: jedno zapytanie");
            Seen s = seen.get(0);
            eq("POST", s.method, "metoda");
            eq("/v1beta/models/ok:generateContent", s.path, "ścieżka");
            eq(KEY, s.key, "klucz w nagłówku, bez spacji");
            contains(s.contentType, "application/json", "typ treści");
            notContains(s.path, KEY, "klucz nie jest w adresie");
            Map<String, Object> body = Json.asObject(Json.parse(s.body));
            List<Object> contents = Json.asArray(body.get("contents"));
            eq(3, contents.size(), "ok: historia + pytanie");
            eq("Drugie pytanie?", Json.asString(Json.asObject(Json.asArray(Json.asObject(contents.get(2)).get("parts")).get(0)).get("text")), "ok: ostatnie pytanie");
            eq("System.", Json.asString(Json.asObject(Json.asArray(Json.asObject(body.get("systemInstruction")).get("parts")).get(0)).get("text")), "ok: polecenie systemowe");

            // „models/” w nazwie i błędna nazwa
            seen.clear();
            Gemini.ask(base, KEY, "models/ok", null, hist, "q", 2000, 2000, fast);
            eq("/v1beta/models/ok:generateContent", seen.get(0).path, "models/ jest zdejmowane");
            seen.clear();
            r = Gemini.ask(base, KEY, "złe/imię", null, hist, "q", 2000, 2000, fast);
            eq("/v1beta/models/" + Gemini.DEFAULT_MODEL + ":generateContent", seen.get(0).path, "błędna nazwa zamieniona na domyślną");
            contains(r.error, "Nie znaleziono modelu", "serwer nie zna domyślnego modelu: 404");

            // chwilowy błąd i ponowienie
            seen.clear();
            r = Gemini.ask(base, KEY, "retry503", null, hist, "q", 2000, 2000, fast);
            ok(r.ok(), "retry503: " + r.error);
            eq("Po ponowieniu.", r.text, "retry503: treść");
            eq(2, counts.get("retry503").get(), "retry503: dwa zapytania");

            // limit: kilka prób i czytelny błąd
            r = Gemini.ask(base, KEY, "limit429", null, hist, "q", 2000, 2000, fast);
            ok(!r.ok(), "limit429");
            eq(429, r.status, "limit429: kod");
            eq(3, counts.get("limit429").get(), "limit429: trzy próby (pierwsza + dwa ponowienia)");
            contains(r.error, "Limit zapytań", "limit429: opis");
            contains(r.error, "Quota exceeded", "limit429: treść serwera");

            // zły klucz: bez ponawiania
            r = Gemini.ask(base, KEY, "badkey", null, hist, "q", 2000, 2000, fast);
            eq(1, counts.get("badkey").get(), "badkey: jedno zapytanie");
            contains(r.error, "Klucz API Gemini jest nieprawidłowy", "badkey: opis");

            // serwer odsyła klucz w błędzie: nie może trafić do komunikatu
            r = Gemini.ask(base, KEY, "echo", null, hist, "q", 2000, 2000, fast);
            ok(!r.ok(), "echo");
            notContains(r.error, KEY, "klucz zakryty w błędzie");
            contains(r.error, "***", "zamiast klucza gwiazdki");

            // strona logowania do Wi-Fi zamiast JSON-a
            r = Gemini.ask(base, KEY, "html", null, hist, "q", 2000, 2000, fast);
            contains(r.error, "Zaloguj się do sieci Wi-Fi", "html: początek treści");

            // brak odpowiedzi na czas
            r = Gemini.ask(base, KEY, "slow", null, hist, "q", 2000, 100, fast);
            ok(!r.ok(), "slow");
            contains(r.error, "nie odpowiedział na czas", "slow: opis");
            eq(3, counts.get("slow").get(), "slow: trzy próby");

            // pusty klucz: żadnego zapytania
            seen.clear();
            r = Gemini.ask(base, "   ", "ok", null, hist, "q", 2000, 2000, fast);
            contains(r.error, "Brak klucza", "pusty klucz");
            eq(0, seen.size(), "pusty klucz: nic nie wysłano");
        } finally {
            server.stop(0);
        }

        // zamknięty port
        int port;
        try (ServerSocket ss = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = ss.getLocalPort();
        }
        Gemini.Result r = Gemini.ask("http://127.0.0.1:" + port, KEY, "ok", null, hist, "q", 500, 500, fast);
        ok(!r.ok(), "zamknięty port");
        contains(r.error, "Nie udało się połączyć z Gemini", "zamknięty port: opis");
        eq(0, r.status, "zamknięty port: brak kodu HTTP");
    }

    // ------------------------------------------------------- pamięć rozmowy

    static void historyTests() {
        eq(0, History.parse(null).size(), "null");
        eq(0, History.parse("").size(), "pusty");
        eq(0, History.parse("nie json").size(), "nie JSON");
        eq(0, History.parse("{}").size(), "obiekt zamiast tablicy");
        eq(0, History.parse("[{\"r\":\"user\",\"t\":\"a\"}]").size(), "nieparzysta liczba");
        eq(0, History.parse("[{\"r\":\"model\",\"t\":\"a\"},{\"r\":\"user\",\"t\":\"b\"}]").size(), "zła kolejność");
        eq(0, History.parse("[{\"r\":\"user\",\"t\":\"a\"},{\"r\":\"user\",\"t\":\"b\"}]").size(), "dwa razy user");
        eq(0, History.parse("[{\"r\":\"user\"},{\"r\":\"model\",\"t\":\"b\"}]").size(), "brak tekstu");
        eq(0, History.parse("[1,2]").size(), "złe elementy");

        List<Gemini.Turn> h = History.add(new ArrayList<>(), "Pytanie \"1\"?", "Odpowiedź\n1.");
        eq(2, h.size(), "pierwsza wymiana");
        List<Gemini.Turn> back = History.parse(History.toJson(h));
        eq(2, back.size(), "odczyt po zapisie");
        eq("Pytanie \"1\"?", back.get(0).text, "tekst pytania");
        eq("model", back.get(1).role, "rola odpowiedzi");
        eq("Odpowiedź\n1.", back.get(1).text, "tekst odpowiedzi");

        // limit liczby wypowiedzi
        List<Gemini.Turn> many = new ArrayList<>();
        for (int i = 1; i <= 30; i++) many = History.add(many, "p" + i, "o" + i);
        eq(History.MAX_MESSAGES, many.size(), "najwyżej tyle wypowiedzi");
        eq("p21", many.get(0).text, "najstarsze zostały usunięte parami");
        eq("o30", many.get(many.size() - 1).text, "ostatnia odpowiedź zostaje");
        eq("user", many.get(0).role, "zaczyna się od pytania");

        // limit znaków
        String big = "x".repeat(7000);
        List<Gemini.Turn> heavy = new ArrayList<>();
        for (int i = 0; i < 10; i++) heavy = History.add(heavy, big, big);
        ok(History.chars(heavy) <= History.MAX_CHARS, "razem nie więcej niż limit znaków: " + History.chars(heavy));
        ok(heavy.size() >= 2 && heavy.size() % 2 == 0, "zostaje przynajmniej ostatnia wymiana");
        eq("user", heavy.get(0).role, "po przycięciu zaczyna się od pytania");

        // ogromna wymiana: ostatnia zawsze zostaje, tekst przycięty
        String huge = "y".repeat(50000);
        List<Gemini.Turn> one = History.add(new ArrayList<>(), huge, huge);
        eq(2, one.size(), "ogromna wymiana zostaje");
        ok(one.get(0).text.length() <= History.MAX_TEXT + 1, "pytanie przycięte");
        ok(one.get(1).text.endsWith("…"), "odpowiedź przycięta z wielokropkiem");

        // przycięcie nie rozcina pary zastępczej
        String emoji = "a".repeat(History.MAX_TEXT - 1) + "😀😀";
        List<Gemini.Turn> e = History.add(new ArrayList<>(), emoji, "ok");
        String cut = e.get(0).text;
        ok(!Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "bez samotnej połówki na końcu");

        // zapis zawsze da się odczytać
        Random rnd = new Random(11);
        for (int i = 0; i < 2000; i++) {
            List<Gemini.Turn> t = new ArrayList<>();
            int n = rnd.nextInt(6);
            for (int j = 0; j < n; j++) t = History.add(t, randomString(rnd), randomString(rnd));
            List<Gemini.Turn> again = History.parse(History.toJson(t));
            eq(t.size(), again.size(), "rozmiar po zapisie i odczycie, próba " + i);
            for (int j = 0; j < t.size(); j++) {
                eq(t.get(j).role, again.get(j).role, "rola " + j);
                eq(t.get(j).text, again.get(j).text, "tekst " + j);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        jsonTests();
        requestTests();
        parseTests();
        serverTests();
        historyTests();
        System.out.println("OK: " + checks + " sprawdzeń");
    }
}
