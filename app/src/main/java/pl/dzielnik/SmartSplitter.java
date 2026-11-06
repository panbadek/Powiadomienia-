package pl.dzielnik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dzielenie inteligentne: tnie tam, gdzie tekst „sam się kończy”, a nie tam, gdzie akurat skończyło się miejsce.
 *
 * Każde miejsce, w którym mogłaby zacząć się nowa część, ma koszt cięcia, a każda część kosztuje {@link #CHUNK}.
 * Wybieram podział o najmniejszej sumie kosztów, więc:
 * - sekcja (kawałek tekstu między pustymi wierszami), która mieści się w limicie, zostaje w całości,
 *   a kilka krótkich sekcji dzieli jedną część, jeśli się mieszczą,
 * - sekcja dłuższa niż limit jest cięta między pozycjami listy, potem między zdaniami, potem między słowami,
 *   a znak po znaku dopiero wtedy, gdy samo jedno słowo nie mieści się w limicie,
 * - nagłówek (# Tytuł, **Tytuł**, wiersz kończący się dwukropkiem) nie zostaje sam na końcu części,
 * - punktor ani numer („-”, „•”, „1.”, „a)”) nie zostaje sam na końcu części.
 *
 * Koszt cięcia (im niżej, tym lepsze miejsce); część dodatkowa kosztuje {@link #CHUNK}, więc jedna część więcej
 * jest tańsza niż przecięcie sekcji w środku, ale nie tańsza niż dwa takie przecięcia:
 * <pre>
 *   0  BLANK   przed wierszem poprzedzonym pustym wierszem (granica sekcji)
 *   1  GROUP   przed nagłówkiem w tej samej sekcji
 *   4  HARD    między wierszami: poprzedni kończy się znakiem końca (. ! ? … : ;) albo następny zaczyna się punktorem
 *   5  SENT    między zdaniami w jednym wierszu (jak w zwykłym dzieleniu)
 *   7  SOFT    między wierszami bez znaku końca (zawinięty tekst, lista „kraj – stolica”)
 *  24  WORD    między słowami (w środku wiersza albo zdania)
 *  30  ORPHAN  tuż za nagłówkiem
 *  64  CHAR    w środku słowa dłuższego niż limit
 * 100  MARKER  tuż za punktorem lub numerem na początku wiersza
 * </pre>
 * Przy remisie każde cięcie wypada jak najpóźniej, więc części są wypełniane do limitu, o ile nie wymaga to
 * gorszego cięcia. Treść się nie zmienia: nic nie jest dopisywane, usuwane są tylko białe znaki na brzegach części.
 * Czas i pamięć rosną liniowo z długością tekstu (kolejka monotoniczna zamiast przeglądania całego okna).
 *
 * Klasa nie korzysta z niczego z Androida, więc da się ją testować zwykłą Javą.
 */
final class SmartSplitter {

    /** Powyżej tej długości tekstu {@link Splitter} dzieli zwykłym algorytmem (pamięć ok. 20 bajtów na słowo). */
    static final int MAX_CHARS = 1_000_000;

    static final int CHUNK = 3;
    static final int BLANK = 0;
    static final int GROUP = 1;
    static final int HARD = 4;
    static final int SENT = 5;
    static final int SOFT = 7;
    static final int WORD = 24;
    static final int ORPHAN = 30;
    static final int CHAR = 64;
    static final int MARKER = 100;

    private SmartSplitter() {}

    /**
     * Miejsca, w których może zacząć się nowa część: {@code pos[k]} to początek części, {@code end[k]} koniec treści
     * poprzedniej części (bez białych znaków), {@code cost[k]} koszt takiego cięcia. Wpis o indeksie {@code size}
     * oznacza koniec tekstu: {@code pos} i {@code end} wskazują koniec ostatniego słowa, koszt wynosi 0.
     */
    static final class Cuts {
        int[] pos = new int[256];
        int[] end = new int[256];
        int[] cost = new int[256];
        int size;

        void add(int p, int e, int c) {
            if (size + 1 >= pos.length) {
                int cap = pos.length * 2;
                pos = Arrays.copyOf(pos, cap);
                end = Arrays.copyOf(end, cap);
                cost = Arrays.copyOf(cost, cap);
            }
            pos[size] = p;
            end[size] = e;
            cost[size] = c;
            size++;
        }

        void finish(int textEnd) {
            if (size + 1 >= pos.length) {
                pos = Arrays.copyOf(pos, size + 2);
                end = Arrays.copyOf(end, size + 2);
                cost = Arrays.copyOf(cost, size + 2);
            }
            pos[size] = textEnd;
            end[size] = textEnd;
            cost[size] = 0;
        }
    }

    /** Dzieli tekst o ujednoliconych końcach wierszy ({@code \n}); {@code limit} co najmniej {@link Splitter#MIN_LIMIT}. */
    static List<String> split(String t, int limit) {
        List<String> out = new ArrayList<>();
        Cuts c = analyze(t, limit);
        if (c.size == 0) return out;
        int[] next = plan(c, limit);
        for (int i = 0; i < c.size; ) {
            int j = next[i];
            out.add(t.substring(c.pos[i], c.end[j]));
            i = j;
        }
        return out;
    }

    // ---------- wyszukiwanie miejsc cięcia ----------

    /**
     * Przegląda tekst wiersz po wierszu (puste wiersze zaznaczają granice sekcji) i słowo po słowie, i dla każdego słowa
     * zapisuje koszt cięcia tuż przed nim. W słowie dłuższym niż limit dodaje cięcia w każdym miejscu poza środkiem
     * pary zastępczej (emoji), żeby dało się je podzielić.
     */
    static Cuts analyze(String t, int limit) {
        Cuts c = new Cuts();
        int n = t.length();
        boolean haveText = false;       // był już jakiś niepusty wiersz
        boolean blankBefore = false;    // między poprzednim niepustym wierszem a tym był pusty wiersz
        boolean prevHeading = false;
        boolean prevTerminal = false;
        int lastEnd = 0;                // koniec ostatniego słowa
        int p = 0;
        while (p <= n) {
            int q = t.indexOf('\n', p);
            if (q < 0) q = n;
            int a = p, b = q;
            while (a < b && Splitter.ws(t.charAt(a))) a++;
            while (b > a && Splitter.ws(t.charAt(b - 1))) b--;
            if (b > a) {
                boolean heading = isHeading(t, a, b);
                int index = 0;
                int prevA = 0, prevB = 0;
                int i = a;
                while (i < b) {
                    while (i < b && Splitter.ws(t.charAt(i))) i++;
                    if (i >= b) break;
                    int j = i;
                    while (j < b && !Splitter.ws(t.charAt(j))) j++;

                    int cost;
                    int before;
                    if (index == 0) {
                        before = lastEnd;
                        if (!haveText) cost = 0;
                        else if (prevHeading) cost = ORPHAN;
                        else if (blankBefore) cost = BLANK;
                        else if (heading) cost = GROUP;
                        else if (prevTerminal || isMarker(t, i, j)) cost = HARD;
                        else cost = SOFT;
                    } else {
                        before = prevB;
                        if (index == 1 && isMarker(t, prevA, prevB)) cost = MARKER;
                        else if (endsSentence(t, prevA, prevB) && startsSentence(t, i)) cost = SENT;
                        else cost = WORD;
                    }
                    c.add(i, before, cost);
                    if (j - i > limit) {
                        for (int k = i + 1; k < j; k++) {
                            if (Character.isLowSurrogate(t.charAt(k)) && Character.isHighSurrogate(t.charAt(k - 1))) continue;
                            c.add(k, k, CHAR);
                        }
                    }
                    prevA = i;
                    prevB = j;
                    lastEnd = j;
                    index++;
                    i = j;
                }
                haveText = true;
                blankBefore = false;
                prevHeading = heading;
                prevTerminal = endsTerminal(t, a, b);
            } else {
                blankBefore = true;
            }
            p = q + 1;
        }
        c.finish(lastEnd);
        return c;
    }

    /**
     * Nagłówek: „# Tytuł” (od jednego do sześciu krzyżyków i spacja), sam pogrubiony wiersz („**Tytuł**”, „__Tytuł__”)
     * albo wiersz kończący się dwukropkiem (też „**Uwaga:**”). Wiersz [a, b) jest już przycięty.
     */
    static boolean isHeading(String t, int a, int b) {
        int k = a;
        while (k < b && t.charAt(k) == '#') k++;
        if (k > a && k - a <= 6 && k < b && Splitter.ws(t.charAt(k))) {
            while (k < b && Splitter.ws(t.charAt(k))) k++;
            if (k < b) return true;
        }
        char d = t.charAt(a);
        if (b - a >= 5 && (d == '*' || d == '_') && t.charAt(a + 1) == d && t.charAt(b - 1) == d && t.charAt(b - 2) == d) {
            boolean letters = false;
            boolean second = false;     // w środku drugi znacznik, np. „**a** i **b**”, to nie nagłówek
            for (int i = a + 2; i < b - 2; i++) {
                char ch = t.charAt(i);
                if (Character.isLetterOrDigit(ch)) letters = true;
                if (ch == d && i + 1 < b - 2 && t.charAt(i + 1) == d) second = true;
            }
            if (letters && !second) return true;
        }
        int e = b;
        while (e > a && (t.charAt(e - 1) == '*' || t.charAt(e - 1) == '_')) e--;
        return e > a && t.charAt(e - 1) == ':';
    }

    /** Wiersz [a, b) kończy się znakiem końca zdania albo wyliczenia (. ! ? … : ;), nie licząc zamykających ) ] ” " ’ ' » * _. */
    static boolean endsTerminal(String t, int a, int b) {
        int e = b;
        while (e > a && ")]”\"’'»*_".indexOf(t.charAt(e - 1)) >= 0) e--;
        return e > a && ".!?…:;".indexOf(t.charAt(e - 1)) >= 0;
    }

    /** Słowo [a, b) kończy zdanie: na końcu ma ., ! lub ?, ewentualnie z zamykającymi znakami (”, ), … itp.). */
    static boolean endsSentence(String t, int a, int b) {
        int e = b;
        while (e > a && Splitter.isEndMark(t.charAt(e - 1))) e--;
        for (int k = e; k < b; k++) {
            char ch = t.charAt(k);
            if (ch == '.' || ch == '!' || ch == '?') return true;
        }
        return false;
    }

    /** Od pozycji {@code from} (po otwierających znakach: „, (, myślnik, spacja) zaczyna się wielka litera. */
    static boolean startsSentence(String t, int from) {
        int n = t.length();
        int m = from;
        while (m < n && Splitter.isOpener(t.charAt(m))) m++;
        return m < n && Character.isUpperCase(t.charAt(m));
    }

    /**
     * Punktor albo numer pozycji: słowo do sześciu znaków bez liter i cyfr („-”, „•”, „✅”, „######”), „1.”, „12)”,
     * „3.1.”, „a)”, „B.”, „(2)”, „(c)”.
     */
    static boolean isMarker(String t, int a, int b) {
        int len = b - a;
        if (len <= 6) {
            boolean any = false;
            for (int k = a; k < b; k++) {
                if (Character.isLetterOrDigit(t.charAt(k))) {
                    any = true;
                    break;
                }
            }
            if (!any) return true;
        }
        char first = t.charAt(a);
        char last = t.charAt(b - 1);
        if (first == '(' && last == ')' && len >= 3) {
            if (len == 3 && Character.isLetter(t.charAt(a + 1))) return true;
            return len <= 5 && digits(t, a + 1, b - 1);
        }
        if (last != '.' && last != ')') return false;
        if (len == 2 && Character.isLetter(first)) return true;
        // cyfry (do trzech), a po nich kropka i znów cyfry, aż do końcowej kropki albo nawiasu
        int k = a;
        while (true) {
            int d = k;
            while (k < b && k - d < 3 && t.charAt(k) >= '0' && t.charAt(k) <= '9') k++;
            if (k == d) return false;
            if (k == b - 1) return true;
            if (t.charAt(k) != '.') return false;
            k++;
        }
    }

    private static boolean digits(String t, int a, int b) {
        if (b <= a) return false;
        for (int k = a; k < b; k++) {
            char ch = t.charAt(k);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    // ---------- wybór cięć ----------

    /**
     * Wybiera cięcia o najmniejszej sumie kosztów. {@code total[i]} to najmniejszy koszt podziału tekstu od miejsca i
     * do końca: część od i do j kosztuje {@link #CHUNK} plus koszt cięcia j, o ile się mieści (długość od {@code pos[i]}
     * do {@code end[j]} nie przekracza limitu). Wszystkie j, które się mieszczą, tworzą okno przesuwające się w lewo
     * razem z i, więc najmniejszą wartość w oknie wskazuje przód kolejki monotonicznej. Wartości równe zostają obok
     * siebie, a przód ma największy indeks, więc przy remisie wybieram cięcie najpóźniejsze.
     *
     * @return {@code next[i]}: miejsce, w którym kończy się część zaczynająca się w i (m oznacza koniec tekstu)
     */
    static int[] plan(Cuts c, int limit) {
        int m = c.size;
        int[] next = new int[m];
        int[] total = new int[m + 1];
        int[] queue = new int[m + 1];
        int head = 0, tail = 0;
        int reach = m;
        for (int i = m - 1; i >= 0; i--) {
            int j = i + 1;
            int value = c.cost[j] + total[j];
            while (tail > head && c.cost[queue[tail - 1]] + total[queue[tail - 1]] > value) tail--;
            queue[tail++] = j;
            while (reach > i && c.end[reach] - c.pos[i] > limit) reach--;
            if (reach == i) throw new IllegalStateException("nie da się podzielić tekstu na części po " + limit + " znaków");
            while (queue[head] > reach) head++;
            next[i] = queue[head];
            total[i] = CHUNK + c.cost[next[i]] + total[next[i]];
        }
        return next;
    }
}
