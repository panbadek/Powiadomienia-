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
import java.util.List;
import java.util.Map;

/**
 * Rozmowa z Gemini przez REST (generateContent). Czysta Java, bez Androida,
 * dzięki temu cały obieg da się sprawdzić na sztucznym serwerze HTTP.
 */
final class Gemini {

    /** Adres API. Nie jest stały tylko po to, żeby testy mogły skierować aplikację na sztuczny serwer. */
    static volatile String DEFAULT_BASE = "https://generativelanguage.googleapis.com";

    /** Lekki model z największym darmowym limitem dziennym. Wyższy model można wpisać w aplikacji. */
    static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";

    /** Odpowiedzi czytam w powiadomieniach, więc ma być zwykły tekst z pełnymi zdaniami (po nich tnie Splitter). */
    static final String SYSTEM = "Twoje odpowiedzi użytkownik czyta w powiadomieniach na telefonie lub zegarku. "
            + "Odpowiadaj po polsku, chyba że użytkownik pisze w innym języku. "
            + "Pisz zwięźle i zwykłym tekstem, bez Markdown (bez gwiazdek, nagłówków, tabel i bloków kodu). "
            + "Używaj pełnych zdań: każde zaczynaj wielką literą i kończ kropką. "
            + "Listy zapisuj jako zwykłe zdania.";

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private Gemini() {}

    /** Jedna wypowiedź w rozmowie. role to "user" albo "model". */
    static final class Turn {
        final String role;
        final String text;

        Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    static final class Result {
        /** Odpowiedź Gemini albo null przy błędzie. */
        final String text;
        /** Opis błędu po polsku albo null, gdy wszystko się udało. */
        final String error;
        /** Kod HTTP; 0, gdy nie doszło do odpowiedzi serwera. */
        final int status;

        private Result(String text, String error, int status) {
            this.text = text;
            this.error = error;
            this.status = status;
        }

        static Result ok(String text, int status) {
            return new Result(text, null, status);
        }

        static Result fail(String error, int status) {
            return new Result(null, error, status);
        }

        boolean ok() {
            return error == null;
        }
    }

    /** Treść wysyłana do Gemini: powiadomienie, na które odpowiadam, plus moja odpowiedź. */
    static String compose(String notificationText, String reply) {
        String r = reply == null ? "" : reply.trim();
        String n = notificationText == null ? "" : notificationText.trim();
        if (n.isEmpty()) return r;
        return "Odpowiadam na powiadomienie o treści:\n\"\"\"\n" + n + "\n\"\"\"\n\nMoja odpowiedź:\n" + r;
    }

    /** Nazwa modelu bez przedrostka „models/” i bez spacji. Przy błędnej wartości wraca model domyślny. */
    static String cleanModel(String raw) {
        String m = raw == null ? "" : raw.trim();
        if (m.startsWith("models/")) m = m.substring("models/".length());
        return m.matches("[A-Za-z0-9._-]{1,80}") ? m : DEFAULT_MODEL;
    }

    /** Pytanie z całą dotychczasową rozmową. Przy chwilowych błędach ponawia po podanych przerwach. */
    static Result ask(String baseUrl, String apiKey, String model, String system, List<Turn> history,
                      String userText, int connectMs, int readMs, int[] retryDelaysMs) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) return Result.fail("Brak klucza API Gemini. Wpisz go w aplikacji Powiadomienia.", 0);
        String url = baseUrl + "/v1beta/models/" + cleanModel(model) + ":generateContent";
        byte[] body = buildRequest(system, history, userText).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            Result r;
            try {
                r = post(url, key, body, connectMs, readMs);
            } catch (IOException e) {
                r = Result.fail("Nie udało się połączyć z Gemini: " + describe(e) + ".", 0);
            }
            if (r.ok() || !retryable(r.status) || attempt >= retryDelaysMs.length) return redact(r, key);
            try {
                Thread.sleep(retryDelaysMs[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail("Przerwano pytanie do Gemini.", 0);
            }
        }
    }

    static boolean retryable(int status) {
        return status == 0 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    static String buildRequest(String system, List<Turn> history, String userText) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        if (system != null && !system.isEmpty()) {
            b.append("\"systemInstruction\":{\"parts\":[{\"text\":").append(Json.quote(system)).append("}]},");
        }
        b.append("\"contents\":[");
        for (Turn t : history) {
            String role = "model".equals(t.role) ? "model" : "user";
            b.append("{\"role\":\"").append(role).append("\",\"parts\":[{\"text\":")
                    .append(Json.quote(t.text)).append("}]},");
        }
        b.append("{\"role\":\"user\",\"parts\":[{\"text\":").append(Json.quote(userText)).append("}]}");
        return b.append("]}").toString();
    }

    /** Zamienia odpowiedź serwera (kod HTTP i treść) na tekst odpowiedzi albo opis błędu. */
    static Result parseResponse(int status, String body) {
        Map<String, Object> root = null;
        try {
            root = Json.asObject(Json.parse(body));
        } catch (RuntimeException ignored) {
            // niżej zgłaszam błąd z kodem i początkiem treści
        }
        if (status != 200) return Result.fail(describeError(status, root, body), status);
        if (root == null) {
            return Result.fail("Gemini odpowiedziało czymś, czego nie rozumiem: " + clip(body, 150), status);
        }

        StringBuilder text = new StringBuilder();
        String finish = null;
        List<Object> candidates = Json.asArray(root.get("candidates"));
        if (candidates != null && !candidates.isEmpty()) {
            Map<String, Object> first = Json.asObject(candidates.get(0));
            if (first != null) {
                finish = Json.asString(first.get("finishReason"));
                Map<String, Object> content = Json.asObject(first.get("content"));
                List<Object> parts = content == null ? null : Json.asArray(content.get("parts"));
                if (parts != null) {
                    for (Object p : parts) {
                        Map<String, Object> part = Json.asObject(p);
                        if (part == null || Boolean.TRUE.equals(part.get("thought"))) continue;
                        String t = Json.asString(part.get("text"));
                        if (t != null) text.append(t);
                    }
                }
            }
        }
        String answer = text.toString().trim();
        if (!answer.isEmpty()) return Result.ok(answer, status);

        Map<String, Object> feedback = Json.asObject(root.get("promptFeedback"));
        String blocked = feedback == null ? null : Json.asString(feedback.get("blockReason"));
        if (blocked != null) return Result.fail("Gemini nie przyjęło pytania (powód: " + blocked + ").", status);
        if (finish != null && !finish.equals("STOP")) {
            return Result.fail("Gemini nie dało odpowiedzi (powód: " + finish + ").", status);
        }
        return Result.fail("Gemini zwróciło pustą odpowiedź.", status);
    }

    private static String describeError(int status, Map<String, Object> root, String body) {
        String message = null;
        Map<String, Object> err = root == null ? null : Json.asObject(root.get("error"));
        if (err != null) message = Json.asString(err.get("message"));
        if (message == null) message = clip(body, 200);
        String detail = message.isEmpty() ? "" : " Serwer napisał: " + clip(message, 300);

        if (status == 400) {
            if (message.contains("API key not valid") || message.contains("API_KEY_INVALID")) {
                return "Klucz API Gemini jest nieprawidłowy. Sprawdź go w ustawieniach aplikacji.";
            }
            return "Gemini odrzuciło zapytanie (400)." + detail;
        }
        if (status == 401 || status == 403) {
            return "Gemini odmówiło dostępu (" + status + "). Sprawdź klucz API i to, czy ma dostęp do wybranego modelu."
                    + detail;
        }
        if (status == 404) return "Nie znaleziono modelu. Sprawdź jego nazwę w ustawieniach aplikacji." + detail;
        if (status == 429) {
            return "Limit zapytań do Gemini został przekroczony (429). Poczekaj chwilę albo wybierz model z większym "
                    + "darmowym limitem." + detail;
        }
        if (status >= 500) return "Serwer Gemini ma awarię albo jest przeciążony (" + status + "). Spróbuj za chwilę." + detail;
        return "Gemini zwróciło błąd " + status + "." + detail;
    }

    private static Result post(String url, String key, byte[] body, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("x-goog-api-key", key);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            return parseResponse(status, text);
        } finally {
            c.disconnect();
        }
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

    /** Klucz nie może trafić do żadnego komunikatu. */
    private static Result redact(Result r, String key) {
        if (r.ok() || key.isEmpty() || r.error == null || !r.error.contains(key)) return r;
        return Result.fail(r.error.replace(key, "***"), r.status);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
