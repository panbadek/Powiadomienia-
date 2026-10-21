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

    // liczniki pokazujące, że test losowy naprawdę dotyka podziału po wierszach (sprawdzane po pętlach)
    static int chunksTotal = 0, oversize = 0, linesWhole = 0, linesGreedy = 0, wrapCuts = 0, listDiff = 0;

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

    /** Końce wierszy jak w Splitter.split: \r\n i \r to \n. */
    static String norm(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    static boolean isWs(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Niepuste wiersze fragmentu [from, to), bez białych znaków na brzegach, jako {początek, koniec}. */
    static List<int[]> lines(String t, int from, int to) {
        List<int[]> r = new ArrayList<>();
        int p = from;
        while (p < to) {
            int q = t.indexOf('\n', p);
            if (q < 0 || q > to) q = to;
            int a = p, b = q;
            while (a < b && isWs(t.charAt(a))) a++;
            while (b > a && isWs(t.charAt(b - 1))) b--;
            if (b > a) r.add(new int[]{a, b});
            p = q + 1;
        }
        return r;
    }

    /** Wzorzec dla listy bez [.!?]: chciwe pakowanie całych wierszy (każdy musi mieścić się w limicie). */
    static List<String> refLineParts(String t, int limit) {
        List<String> out = new ArrayList<>();
        int cs = -1, ce = -1;
        for (int[] l : lines(t, 0, t.length())) {
            if (cs < 0) {
                cs = l[0];
                ce = l[1];
            } else if (l[1] - cs <= limit) {
                ce = l[1];
            } else {
                out.add(t.substring(cs, ce));
                cs = l[0];
                ce = l[1];
            }
        }
        if (cs >= 0) out.add(t.substring(cs, ce));
        return out;
    }

    /** Lista bez znaków końca zdania i z wierszami mieszczącymi się w limicie: wynik ma być taki jak z prostego wzorca. */
    static boolean checkListDifferential(String text, int limit) {
        String n = norm(text);
        for (int[] l : lines(n, 0, n.length())) if (l[1] - l[0] > limit) return false;
        eq(refLineParts(n, limit), Splitter.split(text, limit), "lista: pakowanie wierszy, limit " + limit + "\n[" + text + "]");
        return true;
    }

    // ---------- niezmienniki podziału dla dowolnego tekstu ----------
    static void checkInvariants(String text, int limit) {
        String norm = norm(text);
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
            int at = norm.indexOf(p, pos);
            ok(at >= 0, "część nie jest fragmentem oryginału: [" + p + "]");
            placed.add(new int[]{at, at + p.length()});
            pos = at + p.length();
        }

        // 4) chciwość: pierwsze zdanie części k+1 (jeśli zaczyna zdanie i mieści się w limicie)
        //    nie zmieściłoby się już w części k
        List<int[]> spans = refSpans(norm);
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
            ok(inside, "zdanie mieszczące się w limicie zostało przecięte: [" + norm.substring(sp[0], sp[1]) + "] limit " + limit);
        }

        // 6) zdanie dłuższe niż limit jest dzielone po wierszach (listy i nagłówki nie mają kropek)
        for (int[] sp : spans) {
            if (sp[1] - sp[0] <= limit) continue;
            for (int[] ln : lines(norm, sp[0], sp[1])) {
                String line = norm.substring(ln[0], ln[1]);
                if (ln[1] - ln[0] <= limit) {
                    // 6a) wiersz mieszczący się w limicie leży w całości w jednej części
                    boolean whole = false;
                    for (int[] pl : placed) if (pl[0] <= ln[0] && ln[1] <= pl[1]) whole = true;
                    ok(whole, "wiersz mieszczący się w limicie został przecięty: [" + line + "] limit " + limit + "\n" + text);
                    linesWhole++;
                    // 7) chciwość: część zaczynająca się od tego wiersza nie mogła go dołączyć do poprzedniej
                    for (int k = 1; k < placed.size(); k++) {
                        if (placed.get(k)[0] != ln[0]) continue;
                        ok(ln[1] - placed.get(k - 1)[0] > limit,
                                "część " + (k - 1) + " mogła pomieścić jeszcze wiersz [" + line + "] (limit " + limit + ")\n" + text);
                        linesGreedy++;
                    }
                } else {
                    // 6b) wiersz dłuższy niż limit: część kończąca się w jego środku zaczyna się w tym wierszu,
                    //     kończy na granicy słowa i jest tak długa, jak się da; bez spacji w oknie to twarde cięcie
                    for (int[] pl : placed) {
                        int s = pl[0], e = pl[1];
                        if (e <= ln[0] || e >= ln[1]) continue;
                        wrapCuts++;
                        ok(s >= ln[0], "długi wiersz nie zaczyna nowej części: [" + line + "] limit " + limit + "\n" + text);
                        if (isWs(norm.charAt(e))) {
                            int w = e;
                            while (w < ln[1] && isWs(norm.charAt(w))) w++;
                            int x = w;
                            while (x < ln[1] && !isWs(norm.charAt(x))) x++;
                            ok(x - s > limit, "wiersz cięty za wcześnie, następne słowo jeszcze by się zmieściło: ["
                                    + norm.substring(s, e) + "] limit " + limit + "\n" + text);
                        } else {
                            boolean spaceInside = false;
                            for (int i = s; i < e; i++) if (isWs(norm.charAt(i))) spaceInside = true;
                            ok(!spaceInside, "twarde cięcie, choć w oknie była spacja: [" + norm.substring(s, e) + "] limit " + limit);
                            ok(!Character.isLowSurrogate(norm.charAt(e)), "twarde cięcie w połowie pary zastępczej");
                            ok(e - s == limit || (e - s == limit - 1 && Character.isHighSurrogate(norm.charAt(e))),
                                    "twarde cięcie nie na limicie: " + (e - s) + " zamiast " + limit);
                        }
                    }
                }
            }
        }
    }

    // ---------- generatory tekstów ----------
    static final String[] WORDS = {"ala", "kot", "Łódź", "źrebię", "x", "5", "3.14", "np.", "tzw.", "Ok", "Żaba", "dług", "Ślimak",
            "a", "bardzo", "krótkie", "zdanie", "Piotr", "Zażółć", "gęślą", "jaźń"};
    static final String[] ENDS = {".", ".", ".", "!", "?", "...", ".”", ".)", "?!", ". ", ".*"};
    static final String[] SEPS = {" ", " ", " ", "  ", "\n", " \n", "\n\n", "\t", " „", " - ", " — "};

    /** Zdania z kropkami (dotychczasowy generator). */
    static String randomProse(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int sentences = 1 + rnd.nextInt(25);
        for (int s = 0; s < sentences; s++) {
            int w = 1 + rnd.nextInt(12);
            for (int k = 0; k < w; k++) {
                String word = WORDS[rnd.nextInt(WORDS.length)];
                if (rnd.nextInt(60) == 0) word = "Z" + "z".repeat(10 + rnd.nextInt(60));
                if (k == 0 && rnd.nextInt(8) != 0) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                if (k > 0) sb.append(' ');
                sb.append(word);
            }
            sb.append(ENDS[rnd.nextInt(ENDS.length)]);
            sb.append(SEPS[rnd.nextInt(SEPS.length)]);
        }
        return sb.toString();
    }

    static final String[] LIST_WORDS = {"Maroko", "Rabat", "–", "Algieria", "Algier", "Łódź", "źrebię", "x", "5", "Żaba",
            "dług", "Ślimak", "a", "bardzo", "Zażółć", "gęślą", "jaźń", "Republika", "Zielonego", "Przylądka"};
    static final String[] LINE_SEPS = {"\n", "\n", "\n", "\n", "\n\n", " \n", "\n  ", "\r\n", "\n\t", "\n\n\n"};

    /** Lista: wiersze bez [.!?], czasem puste wiersze, wcięcia, \r\n i bardzo długie słowo. */
    static String randomList(Random rnd) {
        StringBuilder sb = new StringBuilder();
        if (rnd.nextInt(4) == 0) sb.append(rnd.nextBoolean() ? "  " : "\n");
        int lines = 1 + rnd.nextInt(30);
        for (int i = 0; i < lines; i++) {
            int w = 1 + rnd.nextInt(5);
            for (int k = 0; k < w; k++) {
                if (k > 0) sb.append(' ');
                int dice = rnd.nextInt(90);
                if (dice == 0) sb.append("Z".repeat(10 + rnd.nextInt(60)));
                else if (dice == 1) sb.append("😀".repeat(5 + rnd.nextInt(30)));
                else sb.append(LIST_WORDS[rnd.nextInt(LIST_WORDS.length)]);
            }
            if (i + 1 < lines) sb.append(LINE_SEPS[rnd.nextInt(LINE_SEPS.length)]);
        }
        if (rnd.nextInt(4) == 0) sb.append(" \n");
        return sb.toString();
    }

    /** Zdania i listy na przemian. */
    static String randomMixed(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int blocks = 1 + rnd.nextInt(4);
        for (int b = 0; b < blocks; b++) {
            sb.append(rnd.nextBoolean() ? randomProse(rnd) : randomList(rnd));
            sb.append(SEPS[rnd.nextInt(SEPS.length)]);
        }
        return sb.toString();
    }

    /** Tekst zawinięty na sztywno w wiersze o szerokości width (jak skopiowany z PDF-a albo maila). */
    static String hardWrap(String s, int width) {
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (String word : s.trim().split("\\s+")) {
            if (col > 0 && col + 1 + word.length() > width) {
                sb.append('\n');
                col = 0;
            } else if (col > 0) {
                sb.append(' ');
                col++;
            }
            sb.append(word);
            col += word.length();
        }
        return sb.toString();
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

        // ---------- lista bez kropek: jednostką jest wiersz ----------
        // błąd zgłoszony przez użytkownika: lista wpisów traktowana jak jeden ciąg była cięta w środku wpisu
        eq(Arrays.asList("Maroko – Rabat\nEgipt – Kair", "Dżibuti – Dababsbak"),
                Splitter.split("Maroko – Rabat\nEgipt – Kair\nDżibuti – Dababsbak", 40), "lista: cięcie między wpisami, nie w środku wpisu");

        eq(Arrays.asList("Alfa\nBeta", "Gamma", "Delta"), Splitter.split("Alfa\nBeta\nGamma\nDelta", 10), "lista: wiersze pakowane chciwie");
        eq(Arrays.asList("Alfa\n\n  Beta\nGamma"), Splitter.split("Alfa\n\n  Beta\nGamma", 50),
                "tekst mieszczący się w limicie zostaje w całości, z pustymi wierszami i wcięciem");
        eq(Arrays.asList("Aaaa", "Bbbb"), Splitter.split("Aaaa\n   \n\n  \nBbbb", 10), "puste wiersze są pomijane");
        eq(Arrays.asList("Aaaa bbbb cc", "Xxxx yyyy zz"), Splitter.split("Aaaa bbbb cc\nXxxx yyyy zz", 12),
                "wiersz dokładnie na limicie nie jest dzielony");
        eq(Arrays.asList("Aaaa bbbb cc", "Xxxx yyyy", "zzz"), Splitter.split("Aaaa bbbb cc\nXxxx yyyy zzz", 12),
                "wiersz o jeden znak za długi jest dzielony po słowach, poprzedni zostaje w całości");
        eq(Arrays.asList("- punkt pierwszy", "- punkt drugi"), Splitter.split("  - punkt pierwszy\n  - punkt drugi", 20),
                "wcięcia przy wierszach są obcinane");
        eq(Arrays.asList("- punkt pierwszy\n  - punkt drugi"), Splitter.split("  - punkt pierwszy\n  - punkt drugi", 40),
                "wcięcie wewnątrz części zostaje");

        // wiersz dłuższy niż limit jest dzielony po słowach, sąsiednie wiersze zostają w całości
        eq(Arrays.asList("Aaa", "Bbb ccc ddd", "eee fff ggg", "Hhh"),
                Splitter.split("Aaa\nBbb ccc ddd eee fff ggg\nHhh", 12), "lista: za długi wiersz po słowach");
        eq(Arrays.asList("Aaa", "x".repeat(10), "x".repeat(10), "xxxxx\nBbb"),
                Splitter.split("Aaa\n" + "x".repeat(25) + "\nBbb", 10), "lista: wiersz bez spacji cięty twardo");

        // zdania i lista razem: zdanie z kropką zostaje całe, lista dzieli się po wierszach
        eq(Arrays.asList("Oto lista krajów.", "Maroko – Rabat", "Algieria – Algier", "Tunezja – Tunis. Koniec."),
                Splitter.split("Oto lista krajów. Maroko – Rabat\nAlgieria – Algier\nTunezja – Tunis. Koniec.", 30),
                "zdanie, lista i zdanie");

        // lista użytkownika (446 znaków, z nagłówkiem) i druga (454 znaki)
        String africa1 = "Rzędami z północy na południe, w każdym rzędzie od zachodu na wschód:\n\nMaroko – Rabat\nAlgieria – Algier\n"
                + "Tunezja – Tunis\nLibia – Trypolis\nEgipt – Kair\n\nMauretania – Nawakszut\nMali – Bamako\nNiger – Niamey\n"
                + "Czad – Ndżamena\nSudan – Chartum\nErytrea – Asmara\n\nRepublika Zielonego Przylądka – Praia\nSenegal – Dakar\n"
                + "Gambia – Bandżul\nGwinea Bissau – Bissau\nGwinea – Konakry\nBurkina Faso – Wagadugu\nNigeria – Abudża\n"
                + "Etiopia – Addis Abeba\nDżibuti – Dababsbak";
        String africa2 = "Maroko – Rabat\nAlgieria – Algier\nTunezja – Tunis\nLibia – Trypolis\nEgipt – Kair\n\nMauretania – Nawakszut\n"
                + "Mali – Bamako\nNiger – Niamey\nCzad – Ndżamena\nSudan – Chartum\nErytrea – Asmara\n\n"
                + "Republika Zielonego Przylądka – Praia\nSenegal – Dakar\nGambia – Bandżul\nGwinea Bissau – Bissau\nGwinea – Konakry\n"
                + "Burkina Faso – Wagadugu\nNigeria – Abudża\nEtiopia – Addis Abeba\nDżibuti – Dżibuti\n\n"
                + "Sierra Leone – Freetown\nLiberia – Monrowia\nWybrzeże Kości Słoniowej – Jamusukro";
        eq(446, africa1.length(), "długość pierwszej listy");
        eq(454, africa2.length(), "długość drugiej listy");
        eq(Arrays.asList(africa1), Splitter.split(africa1, 446), "pierwsza lista mieści się w całości");
        List<String> a445 = Splitter.split(africa1, 445);
        eq(2, a445.size(), "limit 445: dwie części");
        eq(africa1.substring(0, africa1.lastIndexOf('\n')), a445.get(0), "limit 445: wszystko oprócz ostatniego wpisu");
        eq("Dżibuti – Dababsbak", a445.get(1), "limit 445: ostatni wpis w całości do następnej części");
        List<String> b453 = Splitter.split(africa2, 453);
        eq(Arrays.asList(africa2.substring(0, africa2.lastIndexOf('\n')), "Wybrzeże Kości Słoniowej – Jamusukro"), b453,
                "druga lista, limit 453");
        eq(Splitter.split(africa2, 300), Splitter.split(africa2.replace("\n", "\r\n"), 300), "\\r\\n to to samo co \\n");
        eq(Splitter.split(africa2, 300), Splitter.split(africa2.replace("\n", "\r"), 300), "\\r to to samo co \\n");

        // każdy limit od najdłuższego wiersza w górę: części złożone z całych wierszy, w kolejności, chciwie
        int maxLine1 = 0, maxLine2 = 0;
        for (int[] l : lines(africa1, 0, africa1.length())) maxLine1 = Math.max(maxLine1, l[1] - l[0]);
        for (int[] l : lines(africa2, 0, africa2.length())) maxLine2 = Math.max(maxLine2, l[1] - l[0]);
        eq(69, maxLine1, "najdłuższy wiersz pierwszej listy (nagłówek)");
        eq(37, maxLine2, "najdłuższy wiersz drugiej listy");
        for (int limit = maxLine1; limit <= 460; limit++) {
            ok(checkListDifferential(africa1, limit), "pierwsza lista, limit " + limit);
            checkInvariants(africa1, limit);
        }
        for (int limit = maxLine2; limit <= 460; limit++) {
            ok(checkListDifferential(africa2, limit), "druga lista, limit " + limit);
            checkInvariants(africa2, limit);
        }
        // przy mniejszych limitach długie wiersze są dzielone po słowach, reszta zostaje w całości
        for (int limit = Splitter.MIN_LIMIT; limit < maxLine1; limit++) checkInvariants(africa1, limit);
        for (int limit = Splitter.MIN_LIMIT; limit < maxLine2; limit++) checkInvariants(africa2, limit);

        // ---------- test losowy względem niezależnych niezmienników ----------
        // 1) zdania z kropkami (dotychczasowy generator, ten sam ciąg losowy)
        Random rnd = new Random(12345);
        for (int iter = 0; iter < 30000; iter++) {
            String text = randomProse(rnd);
            int limit = 10 + rnd.nextInt(110);
            checkInvariants(text, limit);
        }
        System.out.println("podział (zdania): OK (" + chunksTotal + " części, " + oversize + " zdań dłuższych niż limit)");

        // 2) listy bez kropek, także z za długimi wierszami; gdy wszystkie wiersze się mieszczą, porównanie z prostym wzorcem
        int before = linesWhole;
        Random r2 = new Random(777);
        for (int iter = 0; iter < 30000; iter++) {
            String text = randomList(r2);
            int limit = 10 + r2.nextInt(110);
            checkInvariants(text, limit);
            if (checkListDifferential(text, limit)) listDiff++;
        }
        ok(listDiff > 8000, "za mało list zbadanych wzorcem: " + listDiff);

        // 3) zdania i listy na przemian
        Random r3 = new Random(4242);
        for (int iter = 0; iter < 20000; iter++) {
            String text = randomMixed(r3);
            checkInvariants(text, 10 + r3.nextInt(110));
        }

        // 4) zdania zawinięte na sztywno (tekst z PDF-a albo maila): długie zdania z końcami wierszy w środku
        Random r4 = new Random(99);
        for (int iter = 0; iter < 20000; iter++) {
            String text = hardWrap(randomProse(r4), 15 + r4.nextInt(60));
            checkInvariants(text, 10 + r4.nextInt(110));
        }
        ok(linesWhole - before > 30000, "za mało sprawdzonych wierszy: " + (linesWhole - before));
        ok(linesGreedy > 5000, "za mało sprawdzonych granic wierszy: " + linesGreedy);
        ok(wrapCuts > 3000, "za mało sprawdzonych cięć długich wierszy: " + wrapCuts);
        System.out.println("podział (listy): OK (" + listDiff + " list porównanych ze wzorcem, " + linesWhole
                + " wierszy w długich zdaniach, " + linesGreedy + " granic części na wierszach, " + wrapCuts + " cięć długich wierszy)");

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
