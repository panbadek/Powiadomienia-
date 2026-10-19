package pl.dzielnik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Najmniejszy potrzebny parser JSON-a plus zapis tekstu w cudzysłowie.
 * Nie zależy od Androida, więc da się go sprawdzić zwykłą Javą.
 * Wartości: Map (obiekt), List (tablica), String, Double, Boolean albo null.
 */
final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {}

    static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    static Object parse(String text) {
        if (text == null) throw new ParseException("pusty dokument");
        Parser p = new Parser(text);
        p.skipSpace();
        Object v = p.value(0);
        p.skipSpace();
        if (p.pos != text.length()) throw p.fail("dane po końcu dokumentu");
        return v;
    }

    /** Tekst jako literał JSON, razem z cudzysłowami. Samotne połówki znaków zastępuję znakiem U+FFFD. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        b.append("\\u00").append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 15));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            b.append(c).append(s.charAt(++i));
                        } else {
                            b.append('�');
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        b.append('�');
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static final String HEX = "0123456789abcdef";

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        ParseException fail(String what) {
            return new ParseException(what + " (pozycja " + pos + ")");
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) throw fail("oczekiwano '" + c + "'");
            pos++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw fail("zbyt głębokie zagnieżdżenie");
            if (pos >= s.length()) throw fail("nieoczekiwany koniec dokumentu");
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw fail("nieoczekiwany znak '" + c + "'");
            }
        }

        void literal(String word) {
            if (!s.startsWith(word, pos)) throw fail("oczekiwano " + word);
            pos += word.length();
        }

        Object object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != '"') throw fail("oczekiwano nazwy pola");
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                m.put(key, value(depth + 1));
                skipSpace();
                if (pos >= s.length()) throw fail("nieskończony obiekt");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw fail("oczekiwano ',' albo '}'");
            }
        }

        Object array(int depth) {
            List<Object> a = new ArrayList<>();
            pos++; // [
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return a;
            }
            while (true) {
                skipSpace();
                a.add(value(depth + 1));
                skipSpace();
                if (pos >= s.length()) throw fail("nieskończona tablica");
                char c = s.charAt(pos++);
                if (c == ']') return a;
                if (c != ',') throw fail("oczekiwano ',' albo ']'");
            }
        }

        String string() {
            pos++; // otwierający cudzysłów
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw fail("nieskończony tekst");
                char c = s.charAt(pos++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) throw fail("nieskończony tekst");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        b.append('"');
                        break;
                    case '\\':
                        b.append('\\');
                        break;
                    case '/':
                        b.append('/');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > s.length()) throw fail("za krótki zapis \\u");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) throw fail("zły zapis \\u");
                            code = code * 16 + d;
                        }
                        pos += 4;
                        b.append((char) code);
                        break;
                    default:
                        throw fail("zły znak po '\\'");
                }
            }
        }

        Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            int digits = digits();
            if (digits == 0) throw fail("oczekiwano cyfry");
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                if (digits() == 0) throw fail("oczekiwano cyfry po kropce");
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (digits() == 0) throw fail("oczekiwano cyfry wykładnika");
            }
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw fail("zła liczba");
            }
        }

        int digits() {
            int n = 0;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
                n++;
            }
            return n;
        }
    }
}
