package pl.dzielnik;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pamięć rozmowy z Gemini: ostatnie pary pytanie-odpowiedź, zapisane jako JSON.
 * Czysta Java, bez Androida.
 */
final class History {

    /** Najwięcej wypowiedzi (po dwie na jedną wymianę). */
    static final int MAX_MESSAGES = 20;
    /** Najwięcej znaków w całej pamięci. */
    static final int MAX_CHARS = 24000;
    /** Najwięcej znaków jednej zapamiętanej wypowiedzi. */
    static final int MAX_TEXT = 8000;

    private History() {}

    /** Wczytuje zapis; przy jakimkolwiek błędzie zwraca pustą rozmowę. */
    static List<Gemini.Turn> parse(String json) {
        List<Gemini.Turn> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            List<Object> arr = Json.asArray(Json.parse(json));
            if (arr == null) return new ArrayList<>();
            for (Object o : arr) {
                Map<String, Object> m = Json.asObject(o);
                String role = m == null ? null : Json.asString(m.get("r"));
                String text = m == null ? null : Json.asString(m.get("t"));
                if (role == null || text == null) return new ArrayList<>();
                out.add(new Gemini.Turn(role, text));
            }
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        // rozmowa ma iść na zmianę: pytanie, odpowiedź, pytanie, odpowiedź...
        if (out.size() % 2 != 0) return new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (!out.get(i).role.equals(i % 2 == 0 ? "user" : "model")) return new ArrayList<>();
        }
        return out;
    }

    static String toJson(List<Gemini.Turn> turns) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < turns.size(); i++) {
            Gemini.Turn t = turns.get(i);
            if (i > 0) b.append(',');
            b.append("{\"r\":").append(Json.quote(t.role)).append(",\"t\":").append(Json.quote(t.text)).append('}');
        }
        return b.append(']').toString();
    }

    /** Dokłada wymianę i usuwa najstarsze, gdy pamięć robi się za duża. Ostatnia wymiana zawsze zostaje. */
    static List<Gemini.Turn> add(List<Gemini.Turn> old, String userText, String modelText) {
        List<Gemini.Turn> n = new ArrayList<>(old);
        n.add(new Gemini.Turn("user", clip(userText)));
        n.add(new Gemini.Turn("model", clip(modelText)));
        while (n.size() > 2 && (n.size() > MAX_MESSAGES || chars(n) > MAX_CHARS)) {
            n.remove(0);
            n.remove(0);
        }
        return n;
    }

    static int chars(List<Gemini.Turn> turns) {
        int sum = 0;
        for (Gemini.Turn t : turns) sum += t.text.length();
        return sum;
    }

    private static String clip(String s) {
        if (s.length() <= MAX_TEXT) return s;
        int end = MAX_TEXT;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
