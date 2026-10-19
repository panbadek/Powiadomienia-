import pl.dzielnik.Measure;
import pl.dzielnik.Splitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zwykły test bez JUnita: javac + java, kończy się błędem przy pierwszej niezgodności. */
public class SplitterTest {

    static int checks = 0;

    static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!expected.equals(actual)) {
            throw new AssertionError(what + "\n  oczekiwano: " + expected + "\n  jest:       " + actual);
        }
    }

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError(what);
    }

    // ---------- niezależny wzorzec podziału na zdania (regex) ----------
    static final Pattern BOUNDARY = Pattern.compile(
            "(?<=[.!?][\\u2026\\u201D\"\\u2019'\\u00BB)\\]*_.!?]{0,20})\\s+"
                    + "(?=[\\u201E\\u201C\"\\u00AB\\u2018'(\\[*_\\u2014\\u2013\\- \\t]*\\p{Lu})");

    static List<int[]> refSpans(String t) {
        List<int[]> r = new ArrayList<>();
        int start = 0;
        while (start < t.length() && Character.isWhitespace(t.charAt(start))) start++;
        Matcher m = BOUNDARY.matcher(t);
        while (m.find()) {
            r.add(new int[]{start, m.start()});
            start = m.end();
        }
        int end = t.length();
        while (end > start && Character.isWhitespace(t.charAt(end - 1))) end--;
        if (end > start) r.add(new int[]{start, end});
        return r;
    }

    static String strip(String s) {
        return s.replaceAll("\\s+", "");
    }

    public static void main(String[] args) {
        // ---------- przykłady ----------
        eq(Arrays.asList("Ala ma kota. Kot ma Alę.", "Ola ma psa."),
                Splitter.split("Ala ma kota. Kot ma Alę. Ola ma psa.", 25), "dwa zdania w części, trzecie przeniesione");

        eq(Arrays.asList("Mam np. jabłko.", "Potem idę."),
                Splitter.split("Mam np. jabłko. Potem idę.", 15), "kropka przed małą literą nie kończy zdania");

        eq(Arrays.asList("Aaaa bbbb. Cccc dddd.", "Eeee ffff."),
                Splitter.split("Aaaa bbbb. Cccc dddd. Eeee ffff.", 22), "zdanie, które się nie mieści, idzie do następnej części");

        eq(Arrays.asList("Aaaa bbbb. Cccc dddd."),
                Splitter.split("Aaaa bbbb. Cccc dddd.", 21), "dokładnie na limicie");

        eq(Arrays.asList("Aaaa bbbb.", "Cccc dddd."),
                Splitter.split("Aaaa bbbb. Cccc dddd.", 20), "o jeden znak za mało");

        eq(Arrays.asList("Czy to działa?", "Tak! Działa."),
                Splitter.split("Czy to działa? Tak! Działa.", 14), "pytajnik i wykrzyknik też kończą zdanie");

        eq(Arrays.asList("Powiedział: „Idę.”", "Potem wyszedł."),
                Splitter.split("Powiedział: „Idę.” Potem wyszedł.", 18), "cudzysłów zamykający po kropce");

        eq(Arrays.asList("Zdanie bez kropki"), Splitter.split("  Zdanie bez kropki \n", 50), "brak kropki na końcu");
        eq(Arrays.asList(), Splitter.split("   \n ", 50), "same spacje");
        eq(Arrays.asList(), Splitter.split("", 50), "pusty tekst");

        // zdanie dłuższe niż limit: dzielone po słowach, a końcówka może dołączyć do następnych zdań
        List<String> long1 = Splitter.split("To jest bardzo długie zdanie bez końca które trzeba pociąć. Krótkie.", 20);
        eq(Arrays.asList("To jest bardzo", "długie zdanie bez", "końca które trzeba", "pociąć. Krótkie."), long1, "zdanie dłuższe niż limit");

        List<String> long2 = Splitter.split("Intro. To jest bardzo długie zdanie. Ok.", 16);
        eq(Arrays.asList("Intro.", "To jest bardzo", "długie zdanie.", "Ok."), long2, "długie zdanie zaczyna nową część");

        // brak spacji: twarde cięcie
        String blob = "A" + "b".repeat(49) + ".";
        List<String> hard = Splitter.split(blob, 20);
        eq(Arrays.asList(20, 20, 11), Arrays.asList(hard.get(0).length(), hard.get(1).length(), hard.get(2).length()), "twarde cięcie");
        eq(blob, String.join("", hard), "twarde cięcie nic nie gubi");

        // znaki spoza BMP nie są cięte w połowie
        String emoji = "😀".repeat(15);
        for (String part : Splitter.split(emoji, 11)) {
            ok(part.length() <= 11 && part.length() % 2 == 0, "para zastępcza cięta w połowie");
        }

        // ---------- test losowy względem niezależnych niezmienników ----------
        String[] words = {"ala", "kot", "Łódź", "źrebię", "x", "5", "3.14", "np.", "tzw.", "Ok", "Żaba", "dług", "Ślimak",
                "a", "bardzo", "krótkie", "zdanie", "Piotr", "Zażółć", "gęślą", "jaźń"};
        String[] ends = {".", ".", ".", "!", "?", "...", ".”", ".)", "?!", ". ", ".*"};
        String[] seps = {" ", " ", " ", "  ", "\n", " \n", "\n\n", "\t", " „", " - ", " — "};
        Random rnd = new Random(12345);
        int chunksTotal = 0, oversize = 0;
        for (int iter = 0; iter < 30000; iter++) {
            StringBuilder sb = new StringBuilder();
            int sentences = 1 + rnd.nextInt(25);
            for (int s = 0; s < sentences; s++) {
                int w = 1 + rnd.nextInt(12);
                for (int k = 0; k < w; k++) {
                    String word = words[rnd.nextInt(words.length)];
                    if (rnd.nextInt(60) == 0) word = "Z" + "z".repeat(10 + rnd.nextInt(60));
                    if (k == 0 && rnd.nextInt(8) != 0) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                    if (k > 0) sb.append(' ');
                    sb.append(word);
                }
                sb.append(ends[rnd.nextInt(ends.length)]);
                sb.append(seps[rnd.nextInt(seps.length)]);
            }
            String text = sb.toString();
            int limit = 10 + rnd.nextInt(110);
            List<String> parts = Splitter.split(text, limit);
            chunksTotal += parts.size();

            // 1) żadna część nie jest pusta, nie ma spacji na brzegach i mieści się w limicie
            for (String p : parts) {
                ok(!p.isEmpty(), "pusta część");
                ok(p.length() <= limit, "część dłuższa niż limit " + limit + ": " + p.length());
                ok(p.equals(p.strip()), "spacje na brzegach części: [" + p + "]");
            }
            // 2) nic nie ginie i kolejność jest zachowana (porównanie bez białych znaków)
            eq(strip(text), strip(String.join("", parts)), "utrata lub zmiana tekstu\n" + text);

            // 3) każda część to ciągły fragment oryginału
            int pos = 0;
            List<int[]> placed = new ArrayList<>();
            for (String p : parts) {
                int at = text.indexOf(p, pos);
                ok(at >= 0, "część nie jest fragmentem oryginału: [" + p + "]");
                placed.add(new int[]{at, at + p.length()});
                pos = at + p.length();
            }

            // 4) chciwość: pierwsze zdanie części k+1 (jeśli zaczyna zdanie i mieści się w limicie)
            //    nie zmieściłoby się już w części k
            List<int[]> spans = refSpans(text);
            for (int[] sp : spans) if (sp[1] - sp[0] > limit) oversize++;
            for (int k = 0; k + 1 < placed.size(); k++) {
                int nextStart = placed.get(k + 1)[0];
                int[] sentence = null;
                for (int[] sp : spans) if (sp[0] == nextStart) sentence = sp;
                if (sentence == null) continue;                       // część zaczyna się w środku długiego zdania
                if (sentence[1] - sentence[0] > limit) continue;      // długie zdanie zawsze zaczyna nową część
                // poprzednia część musiała kończyć się końcem zdania
                int prevEnd = placed.get(k)[1];
                boolean prevEndsSentence = false;
                for (int[] sp : spans) if (sp[1] == prevEnd) prevEndsSentence = true;
                if (!prevEndsSentence) continue;                      // poprzednia część kończyła długie zdanie po słowach
                ok(sentence[1] - placed.get(k)[0] > limit,
                        "część " + k + " mogła pomieścić jeszcze jedno zdanie (limit " + limit + ")\n" + text);
            }
            // 5) zdanie mieszczące się w limicie nigdy nie jest cięte
            for (int[] sp : spans) {
                if (sp[1] - sp[0] > limit) continue;
                boolean inside = false;
                for (int[] pl : placed) if (pl[0] <= sp[0] && sp[1] <= pl[1]) inside = true;
                ok(inside, "zdanie mieszczące się w limicie zostało przecięte: [" + text.substring(sp[0], sp[1]) + "] limit " + limit);
            }
        }
        System.out.println("podział: OK (" + chunksTotal + " części, " + oversize + " zdań dłuższych niż limit)");

        // ---------- Measure ----------
        eq("12345678;10;345678;20;345678;30;3", Measure.generate(33), "początek wzoru");
        String g125 = Measure.generate(125);
        ok(g125.endsWith("45678;120;45"), "końcówka 125: " + g125.substring(100));
        eq(125, g125.length(), "długość 125");
        eq('1', g125.charAt(119), "pozycja 120 to '1'");
        eq('2', g125.charAt(120), "pozycja 121 to '2'");
        eq('0', g125.charAt(121), "pozycja 122 to '0'");
        eq(';', g125.charAt(122), "pozycja 123 to ';'");
        eq('4', g125.charAt(123), "pozycja 124 to '4'");
        eq('5', g125.charAt(124), "pozycja 125 to '5'");

        // niezależny wzór na znak na pozycji p (od 1)
        String big = Measure.generate(60000);
        for (int p = 1; p <= 60000; p++) {
            char expected;
            if (p <= 8) expected = (char) ('0' + p);
            else if (p == 9) expected = ';';
            else {
                int n = (p / 10) * 10;
                String num = Integer.toString(n);
                if (p < n + num.length()) expected = num.charAt(p - n);
                else if (p == n + num.length()) expected = ';';
                else if (p <= n + 8) expected = (char) ('0' + p % 10);
                else expected = ';';
            }
            if (big.charAt(p - 1) != expected) throw new AssertionError("Measure.generate: pozycja " + p);
            checks++;
        }

        // odczyt: nigdy zła liczba; dla końcówki z całą ostatnią liczbą zawsze dobra
        int wrong = 0, unknown = 0, good = 0;
        for (int len = 9; len <= 5000; len++) {
            String full = Measure.generate(len);
            for (int k = 1; k <= Math.min(40, len); k++) {
                String tail = full.substring(len - k);
                // sama cyfra lub kilka cyfr bez ";" może być początkiem całego tekstu - to z założenia niejednoznaczne
                if (tail.indexOf(';') < 0 && "12345678".startsWith(tail)) continue;
                int got = Measure.decode(tail);
                if (got == len) good++;
                else if (got == -1) {
                    unknown++;
                    ok(k < 20, "dla końcówki " + k + " znaków długość " + len + " powinna być odczytana: " + full.substring(len - k));
                } else {
                    wrong++;
                    throw new AssertionError("zła długość " + got + " zamiast " + len + " dla końcówki " + full.substring(len - k));
                }
            }
        }
        ok(wrong == 0, "złe odczyty");
        eq(125, Measure.decode(";120;45"), "przykład użytkownika");
        eq(125, Measure.decode("…45678;120;45"), "z wielokropkiem");
        eq(125, Measure.decode("45678; 120;45 "), "ze spacjami");
        eq(13, Measure.decode("12345678;10;3"), "początek");
        eq(9, Measure.decode("12345678;"), "pierwsza ósemka i średnik");
        eq(5, Measure.decode("12345"), "same cyfry z początku");
        eq(-1, Measure.decode("78;"), "brak liczby");
        eq(-1, Measure.decode(";120;46"), "niezgodne ze wzorem");
        eq(-1, Measure.decode("abc"), "śmieci");
        eq(-1, Measure.decode(""), "pusto");
        System.out.println("pomiar: OK (" + good + " odczytów poprawnych, " + unknown + " bez wystarczającej końcówki, 0 błędnych)");
        System.out.println("razem sprawdzeń: " + checks);
    }
}
