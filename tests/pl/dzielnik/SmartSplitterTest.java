package pl.dzielnik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Test dzielenia inteligentnego. Zwykły test bez JUnita: javac + java, kończy się błędem przy pierwszej niezgodności.
 *
 * Sprawdzam: koszty cięć na ręcznie policzonych przykładach, podział listy słownictwa (dokładnie to, o co chodziło),
 * własności, które muszą zachodzić dla każdego tekstu (długość, kolejność, nic nie ginie), zgodność wyboru cięć z
 * przeglądem wyczerpującym i z prostym algorytmem kwadratowym, zgodność ze zwykłym dzieleniem na prozie oraz to,
 * że sekcja mieszcząca się w limicie nigdy nie jest cięta w środku.
 */
public class SmartSplitterTest {

    static int checks = 0;
    static int randomTexts = 0, bruteChecked = 0, sectionsChecked = 0, proseChecked = 0, chunksSeen = 0;

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

    static List<String> smart(String text, int limit) {
        return Splitter.split(text, limit, true);
    }

    static boolean isBlank(String t, int from, int to) {
        for (int i = from; i < to; i++) if (!Splitter.ws(t.charAt(i))) return false;
        return true;
    }

    static String norm(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Własności każdego podziału: części niepuste, przycięte, nie dłuższe niż limit, po kolei, a między nimi tylko białe znaki. */
    static void properties(String text, int limit, List<String> parts, String what) {
        String t = norm(text);
        int from = 0;
        for (String p : parts) {
            chunksSeen++;
            ok(!p.isEmpty(), what + ": pusta część");
            ok(p.length() <= limit, what + ": część ma " + p.length() + " zn., limit " + limit + "\n" + p);
            ok(!Splitter.ws(p.charAt(0)) && !Splitter.ws(p.charAt(p.length() - 1)), what + ": białe znaki na brzegach części: [" + p + "]");
            int s = from;
            while (s < t.length() && Splitter.ws(t.charAt(s))) s++;
            ok(t.startsWith(p, s), what + ": część nie jest kolejnym fragmentem tekstu:\n[" + p + "]\nod: [" + t.substring(Math.min(s, t.length())) + "]");
            from = s + p.length();
        }
        ok(isBlank(t, from, t.length()), what + ": coś zginęło na końcu tekstu: [" + t.substring(from) + "]");
    }

    // ---------- 1. podstawy ----------

    static void basics() {
        eq(new ArrayList<String>(), Splitter.split(null, 100, true), "null");
        eq(new ArrayList<String>(), smart("", 100), "pusty tekst");
        eq(new ArrayList<String>(), smart("  \n\n \t ", 100), "same białe znaki");
        try {
            smart("abc", Splitter.MIN_LIMIT - 1);
            ok(false, "limit poniżej minimum powinien dać wyjątek");
        } catch (IllegalArgumentException e) {
            ok(e.getMessage().contains("10"), "komunikat o minimalnym limicie: " + e.getMessage());
        }
        eq(Arrays.asList("Ala ma kota."), smart("  Ala ma kota.  \n", 100), "krótki tekst: jedna część bez białych znaków na brzegach");
        eq(Arrays.asList("A\n\nB"), smart("A\r\n\r\nB", 100), "CRLF zamieniam na \\n");
        eq(Arrays.asList("A\n\nB"), smart("A\r\rB", 100), "CR zamieniam na \\n");
        String t = "Ala ma kota. Kot ma Alę. Ala lubi kota.";
        eq(Splitter.split(t, 20), Splitter.split(t, 20, false), "smart = false to zwykłe dzielenie");
        // dokładnie limit: mieści się, o jeden mniej: dwie części
        eq(Arrays.asList("abcde fghij"), smart("abcde fghij", 11), "dokładnie limit");
        eq(Arrays.asList("abcde", "fghij"), smart("abcde fghij", 10), "o jeden za długo: cięcie na spacji");
    }

    // ---------- 2. koszty cięć ----------

    static String costs(String text, int limit) {
        SmartSplitter.Cuts c = SmartSplitter.analyze(norm(text), limit);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.size; i++) sb.append(i == 0 ? "" : " ").append(c.cost[i]);
        return sb.toString();
    }

    static void costTable() {
        // nagłówek, zdania w wierszu, lista
        //   #  T | Ala ma kota. Kot ma Alę. | - a | - b
        eq("0 100 30 24 24 5 24 24 4 100 4 100", costs("# T\n\nAla ma kota. Kot ma Alę.\n- a\n- b", 100),
                "koszty: nagłówek, zdania, punktory");
        // pusty wiersz 0, nagłówek w sekcji 1, za nagłówkiem 30, numer 4, tuż za numerem 100, wiersz bez znaku końca 7
        eq("0 24 24 0 24 24 1 30 4 100 7", costs("Ala ma kota\n\nKot ma Alę\n**Nagłówek**\nTekst\n1. Punkt\nkoniec.", 100),
                "koszty: granice sekcji i wierszy");
        // dwa zdania w wierszu, ale druga część zaczyna się małą literą albo cyfrą: to nie koniec zdania
        eq("0 24 5 24 24 24 24", costs("To jest. Kot ma 5. razy tyle", 100), "koszty: zdanie tylko przed wielką literą");
        // zamykający cudzysłów i nawias po kropce nie przeszkadzają, otwierający przed wielką literą też
        eq("0 5 24", costs("Tak.” „Nie powiem", 100), "koszty: cudzysłowy");
        // numer i punktor na początku wiersza: nie wolno ciąć tuż za nimi, nawet gdy za numerem stoi wielka litera
        eq("0 100 4 100 4 100 4 100", costs("1. Aaa\n2. Bbb\n• Ccc\n(d) Eee", 100), "koszty: różne punktory");
        eq("0 100 4 100 4 100", costs("a) Aaa\n3.1. Bbb\nB. Ccc", 100), "koszty: a), 3.1., B.");
        // wiersz po wierszu kończącym się znakiem końca (4) kontra po wierszu bez niego (7)
        eq("0 24 24 4 24 24", costs("Ala ma kota.\nKot ma psa", 100), "koszty: po kropce");
        eq("0 24 24 7 24 24", costs("Ala ma kota\nKot ma psa", 100), "koszty: bez kropki");
        eq("0 4", costs("aa;\nbb", 100), "koszty: po średniku");
        eq("0 4", costs("Tak.”\nNie", 100), "koszty: po kropce z cudzysłowem");
        eq("0 4", costs("(Tak.)\nNie", 100), "koszty: po kropce z nawiasem");
        eq("0 4", costs("Tak?!\nNie", 100), "koszty: po ?!");
        eq("0 7", costs("Tak,\nNie", 100), "koszty: po przecinku");
        // dwukropek na końcu wiersza robi z niego nagłówek (kara 30 za cięcie za nim), także w kursywie
        eq("0 24 30", costs("Oto lista:\nelement", 100), "koszty: dwukropek");
        eq("0 30", costs("*Uwaga:*\ntekst", 100), "koszty: dwukropek w kursywie");
        // numery do trzech cyfr i w nawiasach to punktory, cztery cyfry już nie
        eq("0 100", costs("100. Aaa", 100), "koszty: numer 100.");
        eq("0 100", costs("(123) Aaa", 100), "koszty: numer (123)");
        eq("0 100", costs("12) Aaa", 100), "koszty: numer 12)");
        eq("0 5", costs("1000. Aaa", 100), "koszty: tysiąc z kropką to koniec zdania");
        eq("0 24", costs("(1234) Aaa", 100), "koszty: (1234) to nie numer");
        // słowo dłuższe niż limit: koszt 64 w każdym miejscu (13 znaków = 12 cięć w środku), potem zwykłe słowa
        eq("0" + " 64".repeat(12) + " 24 24", costs("abcdefghijklm kot ma", 10), "koszty: długie słowo");
        // słowo równe limitowi nie ma cięć w środku
        eq("0 24", costs("abcdefghij kot", 10), "koszty: słowo równe limitowi");
    }

    // ---------- koszt części i kompromisy ----------

    static void tradeOffs() {
        // Lepiej jedna część więcej niż przecięcie wiersza w środku: [ee][ii aa ii][gg] zamiast [ee\nii aa][ii\ngg].
        eq(Arrays.asList("ee", "ii aa ii", "gg"), smart("ee\nii aa ii\ngg", 10), "dodatkowa część zamiast cięcia w środku wiersza");
        // Część kosztuje 3, cięcie między punktami listy 4: lepiej trzy części na granicach sekcji niż dwie z cięciem sekcji.
        eq(Arrays.asList("hh", "cc\n- kk", "ff"), smart("hh\n\ncc\n- kk\n\nff", 10), "część więcej zamiast cięcia między punktami");
        // Dwie części z cięciem bez znaku końca (7) kosztują tyle samo co trzy części z tanimi cięciami (0 i 4):
        // przy remisie wygrywa późniejsze cięcie, czyli części są wypełniane do limitu.
        eq(Arrays.asList("ff\n\ngg", "ff aa\n- ee"), smart("ff\n\ngg\nff aa\n- ee", 10), "remis: późniejsze cięcie");
        // Wypełnianie do limitu: bez remisu między cięciami o tym samym koszcie bierzemy ostatnie możliwe.
        eq(Arrays.asList("aa bb cc dd", "ee ff"), smart("aa bb cc dd ee ff", 12), "cięcia o tym samym koszcie: jak najpóźniej");
    }

    // ---------- 3. słowa dłuższe niż limit ----------

    static void longTokens() {
        eq(Arrays.asList("abcdefghij", "klmnopqrst", "uvwxyz"), smart("abcdefghijklmnopqrstuvwxyz", 10), "słowo bez spacji: cięcie co limit znaków");
        // przed długim słowem jest krótki tekst: dłuższe słowo zaczyna się w tej samej części, a nie w nowej
        eq(Arrays.asList("ab abcdefg", "hijklmnopq", "rstuvwxyz"), smart("ab abcdefghijklmnopqrstuvwxyz", 10), "krótkie słowo + długie słowo");

        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 12; i++) emoji.append("😀");   // 12 buziek po 2 znaki
        List<String> parts = smart(emoji.toString(), 11);
        properties(emoji.toString(), 11, parts, "emoji");
        eq(3, parts.size(), "emoji: liczba części");
        for (String p : parts) {
            ok(p.length() % 2 == 0, "emoji: część ma parzystą liczbę znaków, czyli nie przecięłem pary zastępczej: " + p.length());
            for (int i = 0; i < p.length(); i += 2) {
                ok(Character.isHighSurrogate(p.charAt(i)) && Character.isLowSurrogate(p.charAt(i + 1)), "emoji: całe pary");
            }
        }
        eq(Arrays.asList(10, 10, 4), Arrays.asList(parts.get(0).length(), parts.get(1).length(), parts.get(2).length()), "emoji: długości części");

        // długi adres w środku zdania: najpierw cięcie przed adresem, a adres sam w całości, jeśli się mieści
        String url = "https://example.com/bardzo/dluga/sciezka/do/pliku";
        eq(Arrays.asList("Zobacz tutaj:", url, "Koniec."), smart("Zobacz tutaj: " + url + " Koniec.", 50), "adres mieści się: bez cięcia w środku");
    }

    // ---------- 4. nagłówki i punktory ----------

    static void headingsAndMarkers() {
        // nagłówek nie zostaje sam na końcu części: ucieka do następnej razem z treścią
        String a = "Pierwszy akapit ma kilka słów.\n\n**Tytuł**\nDrugi akapit też.";
        eq(Arrays.asList("Pierwszy akapit ma kilka słów.", "**Tytuł**\nDrugi akapit też."), smart(a, 40), "nagłówek idzie z treścią");
        // to samo z nagłówkiem po pustym wierszu
        String b = "Pierwszy akapit ma kilka słów.\n\n## Tytuł\n\nDrugi akapit też.";
        eq(Arrays.asList("Pierwszy akapit ma kilka słów.", "## Tytuł\n\nDrugi akapit też."), smart(b, 40), "nagłówek ATX po pustym wierszu idzie z treścią");
        // wiersz kończący się dwukropkiem to też nagłówek
        String c = "Pierwszy akapit ma kilka słów.\n\nOto lista:\n- jeden\n- dwa";
        eq(Arrays.asList("Pierwszy akapit ma kilka słów.", "Oto lista:\n- jeden\n- dwa"), smart(c, 45), "dwukropek: lista idzie z nagłówkiem");
        // nagłówek + akapit, który sam by się zmieścił, ale razem z nagłówkiem nie: lepiej przeciąć akapit między zdaniami,
        // niż zostawić nagłówek samego
        eq(Arrays.asList("**Tytuł**\n\nAla ma kota i psa.", "Kot ma Alę i myszy."),
                smart("**Tytuł**\n\nAla ma kota i psa. Kot ma Alę i myszy.", 45), "nagłówek razem z pierwszym zdaniem akapitu");
        // nagłówek + akapit dłuższy niż limit: akapit tnę między zdaniami, nagłówek zostaje z pierwszym zdaniem
        String d = "**Tytuł**\n\nPierwsze zdanie jest krótkie. Drugie zdanie jest nieco dłuższe od pierwszego. Trzecie też.";
        eq(Arrays.asList("**Tytuł**\n\nPierwsze zdanie jest krótkie.", "Drugie zdanie jest nieco dłuższe od pierwszego.", "Trzecie też."),
                smart(d, 50), "nagłówek razem z pierwszym zdaniem długiego akapitu");
        // dwa nagłówki pod rząd
        String e = "Wstęp do tekstu.\n\n# Rozdział\n\n## Podrozdział\n\nTreść podrozdziału.";
        eq(Arrays.asList("Wstęp do tekstu.", "# Rozdział\n\n## Podrozdział\n\nTreść podrozdziału."), smart(e, 60), "kolejne nagłówki idą razem z treścią");
        // dwa pogrubienia w jednym wierszu to nie nagłówek (gdyby były, cięcie za nimi kosztowałoby 30, a nie 7)
        eq("0 24 24 7", costs("**a** i **b**\ntekst", 100), "dwa pogrubienia w wierszu to nie nagłówek");
        // linia z samych gwiazdek to nie nagłówek (pozioma kreska)
        eq("0 7", costs("*****\ntekst", 100), "kreska z gwiazdek to nie nagłówek");
        // „#hasztag” bez spacji i siedem krzyżyków to nie nagłówki
        eq("0 7", costs("#hasztag\ntekst", 100), "hasztag to nie nagłówek");
        eq("0 24 7", costs("####### Tytuł\ntekst", 100), "siedem krzyżyków to nie nagłówek");
        // ale sześć krzyżyków i pogrubienie z dwukropkiem tak
        eq("0 100 30", costs("###### Tytuł\ntekst", 100), "sześć krzyżyków to nagłówek");
        eq("0 30", costs("**Uwaga:**\ntekst", 100), "pogrubiony dwukropek to nagłówek");

        // punktor ani numer nie zostaje sam na końcu części
        for (String marker : new String[]{"-", "•", "1.", "a)", "(3)", "###", "✅", "3.1.", "B."}) {
            String t = "Ala ma kota i psa.\n" + marker + " Dalsza treść listy";
            List<String> parts = smart(t, 23);
            properties(t, 23, parts, "punktor " + marker);
            eq(Arrays.asList("Ala ma kota i psa.", marker + " Dalsza treść listy"), parts, "punktor " + marker + " zostaje z treścią");
        }
    }

    // ---------- 5. lista słownictwa ----------

    static final String VOCAB = String.join("\n",
            "**Ludzie**",
            "- athlete – sportowiec",
            "- champion (be a champion) – mistrz (być mistrzem)",
            "- (goal)keeper – bramkarz",
            "- judge – sędzia (oceniający, np. w gimnastyce)",
            "- obstacle runner – biegacz w biegu z przeszkodami",
            "- Paralympic athlete – sportowiec paraolimpijski",
            "- referee – sędzia (np. w piłce nożnej, boksie)",
            "- umpire – sędzia (np. w tenisie, baseballu)",
            "- winger – skrzydłowy",
            "",
            "**Kibice i widzowie**",
            "- cheer on a team – dopingować drużynę",
            "- spectators – widzowie (na miejscu)",
            "- support a team – kibicować drużynie",
            "- supporters / fans – kibice",
            "- viewers – widzowie (przed telewizorem)",
            "",
            "**Miejsca**",
            "- arena – arena, hala sportowa",
            "- basketball court – boisko do koszykówki",
            "- bench – ławka rezerwowych",
            "- boxing ring – ring bokserski",
            "- finishing line (BrE) / finish line (AmE) – linia mety",
            "- football pitch / field – boisko piłkarskie / boisko",
            "- ice rink – lodowisko",
            "- racetrack – tor wyścigowy (jedno słowo)",
            "- ski slope – stok narciarski",
            "- stadium – stadion",
            "- tennis court – kort tenisowy",
            "- track – bieżnia, tor",
            "",
            "**Sprzęt**",
            "- baseball bat – kij baseballowy",
            "- gear – sprzęt",
            "- goggles – gogle",
            "- golf club – kij golfowy",
            "- puck (in hockey) – krążek (w hokeju)",
            "- racket – rakieta",
            "- ski poles – kijki narciarskie",
            "- skis – narty",
            "- weights – ciężary",
            "- wetsuit – pianka do pływania (jedno słowo)",
            "- whistle – gwizdek",
            "",
            "**Rozgrywki i zawody**",
            "- championship = cup – mistrzostwa = puchar",
            "- compete in – startować w, rywalizować w",
            "- contest – zawody, konkurs",
            "- hold a competition / event – zorganizować zawody / wydarzenie",
            "- league – liga",
            "- tournament – turniej",
            "- trophy – trofeum",
            "",
            "**Akcje w grze**",
            "- kick – kopać, kopnięcie",
            "- pass – podanie, podawać",
            "- rebound – zbiórka (w koszykówce), odbicie",
            "- score – zdobyć (bramkę, punkt); wynik",
            "- shoot – strzelać",
            "",
            "**Zasady i wynik**",
            "- draw (2:2) – remis",
            "- fair play – czysta gra",
            "- foul play – nieczysta gra",
            "- hand out yellow cards – rozdawać żółte kartki",
            "- obey the rules – przestrzegać zasad",
            "- offside – spalony",
            "- tie-break – tie-break, rozgrywka rozstrzygająca",
            "",
            "**Wygrywanie i przegrywanie**",
            "- beat / defeat – pokonać",
            "- beat a champion / another athlete / a team – pokonać mistrza / innego sportowca / drużynę",
            "- lose a competition / match / game / event – przegrać zawody / mecz / grę / wydarzenie",
            "- win a match / game / championship / trophy / title / medal – wygrać mecz / grę / mistrzostwa / trofeum / tytuł / medal",
            "",
            "**Rekordy**",
            "- beat / break a record – pobić rekord",
            "- break a world record – pobić rekord świata",
            "- hold a record – być rekordzistą",
            "- set a (new) record – ustanowić (nowy) rekord",
            "",
            "**Kontuzje i problemy w sporcie**",
            "- accusations of match-fixing – oskarżenia o ustawianie meczów",
            "- be carried off the pitch – zostać zniesionym z boiska",
            "- enhance performance (with drugs) – poprawiać wyniki (dopingiem)",
            "- feign (an) injury – symulować kontuzję",
            "- light punch – lekki cios",
            "- severe injury – poważna kontuzja",
            "- sprain / sprain your ankle – skręcenie / skręcić kostkę",
            "",
            "**Cechy i umiejętności**",
            "- accuracy – celność, precyzja",
            "- balance – równowaga",
            "- confidence – pewność siebie",
            "- coordination – koordynacja",
            "- fitness – sprawność fizyczna",
            "- flexibility – gibkość",
            "- focus – skupienie, koncentracja",
            "- skill – umiejętność",
            "- speed – szybkość",
            "- stamina – wytrzymałość, kondycja",
            "- strength – siła",
            "",
            "**Trening i dyscypliny**",
            "- high jump – skok wzwyż",
            "- long jump – skok w dal",
            "- rowing – wioślarstwo",
            "- train (hard) – (ciężko) trenować",
            "- work out – ćwiczyć",
            "- year-round sport – sport całoroczny",
            "",
            "**Inne**",
            "- leisure time – czas wolny",
            "- open to the public – otwarty dla wszystkich",
            "",
            "**Określenia ilości**",
            "",
            "Z rzeczownikami policzalnymi:",
            "- a few – kilka, parę",
            "- a number of – szereg, pewna liczba",
            "- both – obaj, obie, oba",
            "- many – wiele, dużo",
            "- neither of – żaden z dwóch",
            "- not many = few – niewiele, mało",
            "- several – kilka",
            "",
            "Z rzeczownikami niepoliczalnymi:",
            "- a great deal of – bardzo dużo",
            "- a little – trochę",
            "- not much = little – niewiele, mało",
            "",
            "Z policzalnymi i niepoliczalnymi:",
            "- a lot of / lots of – dużo",
            "- all (of) – wszyscy, wszystkie, cały",
            "- most of – większość",
            "- no – żaden, brak",
            "- none of – żaden z, nic z (gdy są co najmniej 3)",
            "- plenty of – mnóstwo, pod dostatkiem",
            "- some – trochę, kilka, jakieś",
            "",
            "Uwaga: few / little = mało (za mało), a few / a little = trochę (wystarczająco).");

    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static String lastLine(String s) {
        return s.substring(s.lastIndexOf('\n') + 1);
    }

    static List<String> blocks(String t) {
        List<String> r = new ArrayList<>();
        for (String b : norm(t).split("\n[ \t]*\n")) {
            String x = b.trim();
            if (!x.isEmpty()) r.add(x);
        }
        return r;
    }

    static void vocabulary() {
        List<String> parts = smart(VOCAB, 450);
        properties(VOCAB, 450, parts, "słownictwo 450");
        eq(13, parts.size(), "słownictwo 450: liczba części");
        // każda sekcja mieści się w limicie i leży w jednej części w całości; nagłówek „Określenia ilości” razem z pierwszą listą
        List<String> blocks = blocks(VOCAB);
        for (String b : blocks) {
            if (b.length() > 450) continue;
            boolean found = false;
            for (String p : parts) found |= p.contains(b);
            ok(found, "słownictwo 450: sekcja przecięta: " + firstLine(b));
        }
        eq(Arrays.asList("**Ludzie**", "**Kibice i widzowie**", "**Miejsca**", "**Sprzęt**", "**Rozgrywki i zawody**", "**Zasady i wynik**",
                        "**Wygrywanie i przegrywanie**", "**Rekordy**", "**Kontuzje i problemy w sporcie**", "**Cechy i umiejętności**",
                        "**Trening i dyscypliny**", "**Określenia ilości**", "Z policzalnymi i niepoliczalnymi:"),
                firstLines(parts), "słownictwo 450: pierwsze wiersze części");
        ok(parts.get(4).contains("**Akcje w grze**") && parts.get(10).contains("**Inne**"), "słownictwo 450: krótkie sekcje dzielą część");
        ok(parts.get(11).contains("Z rzeczownikami policzalnymi:") && parts.get(11).contains("Z rzeczownikami niepoliczalnymi:"),
                "słownictwo 450: „Określenia ilości” cięte dopiero między podsekcjami");

        // mniejszy limit: sekcje dłuższe niż limit tnę między punktami, nagłówek nigdy sam na końcu, początek części to nagłówek albo punkt
        for (int limit : new int[]{130, 200, 300, 350}) {
            List<String> ps = smart(VOCAB, limit);
            properties(VOCAB, limit, ps, "słownictwo " + limit);
            for (String p : ps) {
                String last = lastLine(p);
                ok(!last.startsWith("**") && !last.endsWith(":"), "słownictwo " + limit + ": nagłówek na końcu części: " + last);
                String first = firstLine(p);
                ok(first.startsWith("- ") || first.startsWith("**") || first.endsWith(":") || first.startsWith("Uwaga"),
                        "słownictwo " + limit + ": część zaczyna się w środku punktu: " + first);
                for (String line : p.split("\n")) {
                    ok(line.isEmpty() || line.startsWith("- ") || line.startsWith("**") || line.endsWith(":") || line.startsWith("Uwaga"),
                            "słownictwo " + limit + ": punkt listy przecięty: " + line);
                }
            }
            for (String b : blocks) {
                if (b.length() > limit) continue;
                boolean found = false;
                for (String p : ps) found |= p.contains(b);
                ok(found, "słownictwo " + limit + ": sekcja mieszcząca się w limicie została przecięta: " + firstLine(b));
            }
        }
        // zwykłe dzielenie na tym samym tekście tnie w środku sekcji (po to jest dzielenie inteligentne)
        boolean classicCuts = false;
        for (String b : blocks) {
            if (b.length() > 450) continue;
            boolean found = false;
            for (String p : Splitter.split(VOCAB, 450)) found |= p.contains(b);
            classicCuts |= !found;
        }
        ok(classicCuts, "zwykłe dzielenie przecina jakąś sekcję tej listy");
    }

    static List<String> firstLines(List<String> parts) {
        List<String> r = new ArrayList<>();
        for (String p : parts) r.add(firstLine(p));
        return r;
    }

    // ---------- losowe teksty ----------

    static final String[] WORDS = ("ala kot dom las okno pies mleko woda ryba zielony szybki wolno dzisiaj jutro bardzo tak nie może powinien "
            + "Ala Kot Dom Las Polska Warszawa Jan Kowalski Nowy Rok Dzień Noc Morze Góra rzeka miasto wieś ąęłńóśźż Łódź Śląsk żółw gęś").split(" ");
    static final String[] ENDS = {".", ".", ".", "!", "?", "…", ".”", ".\"", ".)", "?!", "...", ".”)"};
    static final String[] BULLETS = {"-", "*", "•", "–", "—", "+", ">", "✅", "1.", "2.", "10.", "3.1.", "a)", "B.", "(1)", "(c)", "12)", "###", "##", "1)"};
    static final String[] HEADS = {"# Tytuł", "## Podtytuł dłuższy", "### Pod", "**Pogrubiony**", "__Pod kreską__", "Lista rzeczy:", "**Uwaga:**",
            "#### Sekcja czwarta", "**a** i **b**", "*****", "#hasztag", "Oto przykład:", "Tytuł:"};
    static final String[] SPACES = {" ", " ", " ", " ", "  ", "\t", " "};

    static String pick(Random r, String[] a) {
        return a[r.nextInt(a.length)];
    }

    static String word(Random r) {
        int k = r.nextInt(100);
        if (k < 5) {
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789/:._-";
            StringBuilder sb = new StringBuilder();
            for (int i = 12 + r.nextInt(59); i > 0; i--) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            return sb.toString();
        }
        if (k < 12) return Integer.toString(r.nextInt(100000));
        if (k < 15) return pick(r, new String[]{"„Tak", "(Jeden", "-Dwa", "*Trzy", "Koniec”", "dalej)", "a.b", "tel.(12)", "x*"});
        return pick(r, WORDS);
    }

    static String sentence(Random r) {
        int n = 1 + r.nextInt(9);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String w = word(r);
            if (i == 0) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            if (i > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.append(pick(r, ENDS)).toString();
    }

    static String words(Random r, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1 + r.nextInt(max); i > 0; i--) sb.append(sb.length() == 0 ? "" : " ").append(word(r));
        return sb.toString();
    }

    static String line(Random r) {
        int k = r.nextInt(100);
        if (k < 12) return pick(r, HEADS);
        if (k < 45) return pick(r, BULLETS) + " " + words(r, 8) + (r.nextInt(10) < 4 ? pick(r, ENDS) : "");
        if (k < 55) return words(r, 6);
        StringBuilder sb = new StringBuilder();
        for (int i = 1 + r.nextInt(4); i > 0; i--) sb.append(sb.length() == 0 ? "" : " ").append(sentence(r));
        return sb.toString();
    }

    static String structured(Random r, int scale) {
        StringBuilder sb = new StringBuilder();
        if (r.nextInt(20) == 0) sb.append("\n\n");
        for (int i = 1 + r.nextInt(scale); i > 0; i--) {
            String l = line(r);
            if (r.nextInt(10) == 0) l = pick(r, SPACES) + l;
            if (r.nextInt(10) == 0) l = l + pick(r, SPACES);
            sb.append(l);
            int k = r.nextInt(100);
            if (k < 30) sb.append(pick(r, new String[]{"\n\n", "\n\n\n", "\n \n", "\n\t\n"}));
            else if (k < 33) sb.append(pick(r, new String[]{"\r\n", "\r", "\r\n\r\n"}));
            else if (k < 37) sb.append(' ');
            else sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- 6. wybór cięć: algorytm kwadratowy i przegląd wyczerpujący ----------

    /** To samo co SmartSplitter.plan, ale wprost: dla każdego i przeglądam całe okno; remis rozstrzygam na korzyść późniejszego cięcia. */
    static int[] naivePlan(SmartSplitter.Cuts c, int limit, int[] totalOut) {
        int m = c.size;
        int[] next = new int[m];
        int[] total = new int[m + 1];
        for (int i = m - 1; i >= 0; i--) {
            int best = Integer.MAX_VALUE, bj = -1;
            for (int j = i + 1; j <= m && c.end[j] - c.pos[i] <= limit; j++) {
                int v = c.cost[j] + total[j];
                if (v <= best) {
                    best = v;
                    bj = j;
                }
            }
            if (bj < 0) throw new AssertionError("brak podziału w wzorcu");
            next[i] = bj;
            total[i] = SmartSplitter.CHUNK + best;
        }
        totalOut[0] = total[0];
        return next;
    }

    static int pathCost(SmartSplitter.Cuts c, int[] next) {
        int cost = 0;
        for (int i = 0; i < c.size; i = next[i]) cost += SmartSplitter.CHUNK + c.cost[next[i]];
        return cost;
    }

    /** Najmniejszy koszt po wszystkich podzbiorach cięć (tylko dla niewielu miejsc). */
    static int bruteCost(SmartSplitter.Cuts c, int limit) {
        int m = c.size;
        int best = Integer.MAX_VALUE;
        for (long mask = 0; mask < (1L << (m - 1)); mask++) {
            int start = 0, cost = 0;
            boolean fits = true;
            for (int j = 1; j <= m && fits; j++) {
                boolean cut = j == m || (mask & (1L << (j - 1))) != 0;
                if (!cut) continue;
                if (c.end[j] - c.pos[start] > limit) fits = false;
                cost += SmartSplitter.CHUNK + c.cost[j];
                start = j;
            }
            if (fits && cost < best) best = cost;
        }
        return best;
    }

    static void planAgainstReferences() {
        Random r = new Random(12345);
        int[] limits = {10, 11, 12, 15, 20, 25, 30, 40, 50, 60, 80, 120, 200, 450};
        for (int it = 0; it < 30000; it++) {
            String text = structured(r, new int[]{1, 2, 3, 5, 8, 14, 30}[r.nextInt(7)]);
            int limit = limits[r.nextInt(limits.length)];
            String t = norm(text);
            SmartSplitter.Cuts c = SmartSplitter.analyze(t, limit);
            if (c.size == 0) {
                eq(new ArrayList<String>(), smart(text, limit), "tekst bez słów");
                continue;
            }
            int[] total = new int[1];
            int[] naive = naivePlan(c, limit, total);
            int[] fast = SmartSplitter.plan(c, limit);
            ok(Arrays.equals(naive, fast), "kolejka monotoniczna daje inne cięcia niż algorytm kwadratowy (limit " + limit + "): [" + text + "]");
            eq(total[0], pathCost(c, fast), "koszt wybranej ścieżki");
            if (c.size <= 13) {
                bruteChecked++;
                eq(bruteCost(c, limit), total[0], "przegląd wyczerpujący (limit " + limit + "): [" + text + "]");
            }
            List<String> parts = smart(text, limit);
            properties(text, limit, parts, "losowy tekst (limit " + limit + ")");
            randomTexts++;
        }
    }

    // ---------- 7. zgodność ze zwykłym dzieleniem na prozie ----------

    static void classicOnProse() {
        // Gdy każde zdanie mieści się w limicie, a tekst to zdania rozdzielone pojedynczą spacją, wszystkie cięcia
        // kosztują tyle samo (zdanie) albo dużo więcej (słowo), więc wygrywa najmniej części, a przy remisie najpóźniejsze
        // cięcia: dokładnie tak, jak chciwe pakowanie zdań w zwykłym dzieleniu.
        Random r = new Random(777);
        for (int it = 0; it < 20000; it++) {
            int limit = 20 + r.nextInt(200);
            StringBuilder sb = new StringBuilder();
            for (int s = 1 + r.nextInt(15); s > 0; ) {
                StringBuilder sen = new StringBuilder();
                for (int w = 1 + r.nextInt(10), i = 0; i < w; i++) {
                    String x = WORDS[r.nextInt(WORDS.length)];
                    if (i == 0) x = Character.toUpperCase(x.charAt(0)) + x.substring(1);
                    sen.append(i == 0 ? "" : " ").append(x);
                }
                sen.append(r.nextInt(5) == 0 ? "!" : ".");
                if (sen.length() > limit) continue;
                sb.append(sb.length() == 0 ? "" : " ").append(sen);
                s--;
            }
            String t = sb.toString();
            eq(Splitter.split(t, limit), smart(t, limit), "proza (limit " + limit + "): [" + t + "]");
            proseChecked++;
        }
    }

    // ---------- 8. sekcja mieszcząca się w limicie nie jest cięta ----------

    static String sectionBody(Random r, boolean withHeading) {
        StringBuilder sb = new StringBuilder();
        if (withHeading) sb.append(pick(r, new String[]{"**" + pick(r, WORDS) + "**", "## " + pick(r, WORDS), "Lista " + pick(r, WORDS) + ":"})).append('\n');
        int kind = r.nextInt(3);
        for (int i = 1 + r.nextInt(8); i > 0; i--) {
            if (kind == 0) sb.append("- ").append(words(r, 5)).append(i > 1 ? "\n" : "");
            else if (kind == 1) sb.append(sentence(r)).append(i > 1 ? " " : "");
            else sb.append(words(r, 6)).append(i > 1 ? "\n" : "");
        }
        return sb.toString();
    }

    /** Ostrożnie: za nagłówek uznaję wszystko, co mogłoby nim być (lepiej pominąć sekcję w teście niż mylić się w drugą stronę). */
    static boolean looksLikeHeading(String line) {
        String l = line.trim();
        while (l.endsWith("*") || l.endsWith("_")) l = l.substring(0, l.length() - 1);
        String raw = line.trim();
        return raw.startsWith("#") || raw.startsWith("**") || raw.startsWith("__") || l.endsWith(":");
    }

    static boolean endsWithHeading(String block) {
        return looksLikeHeading(lastLine(block));
    }

    static void sectionsStayWhole() {
        // Dowód: gdyby sekcja B mieściła się w limicie, a mimo to była cięta w środku (koszt cięcia co najmniej 4), to
        // jej przeniesienie w całości do osobnej części dodaje jedną część (3) i usuwa przynajmniej jedno cięcie (4),
        // a granice sekcji kosztują 0. Wyjątek: sąsiedni nagłówek (30), więc takie sekcje pomijam.
        Random r = new Random(4242);
        int[] limits = {40, 60, 80, 100, 130, 160, 200, 300};
        for (int it = 0; it < 20000; it++) {
            List<String> blocks = new ArrayList<>();
            for (int n = 2 + r.nextInt(6); n > 0; n--) {
                blocks.add(r.nextInt(8) == 0 ? pick(r, new String[]{"**Sam nagłówek**", "## Sam nagłówek", "Sam nagłówek:"}) : sectionBody(r, r.nextBoolean()));
            }
            String text = String.join("\n\n", blocks);
            int limit = limits[r.nextInt(limits.length)];
            List<String> parts = smart(text, limit);
            properties(text, limit, parts, "sekcje (limit " + limit + ")");
            int from = 0;
            for (int bi = 0; bi < blocks.size(); bi++) {
                String b = blocks.get(bi);
                int at = text.indexOf(b, from);
                from = at + b.length();
                boolean cleanLeft = bi == 0 || !endsWithHeading(blocks.get(bi - 1));
                boolean cleanRight = !endsWithHeading(b);
                boolean noGroups = true;
                String[] ls = b.split("\n");
                for (int li = 1; li < ls.length; li++) {
                    noGroups &= !looksLikeHeading(ls[li]);     // nagłówek w środku sekcji dałby tanie cięcie (1)
                }
                if (b.length() > limit || !cleanLeft || !cleanRight || !noGroups) continue;
                sectionsChecked++;
                boolean whole = false;
                for (String p : parts) whole |= p.contains(b);
                ok(whole, "sekcja mieszcząca się w limicie (" + b.length() + " <= " + limit + ") została przecięta:\n[" + b + "]\nw tekście:\n" + text + "\nczęści: " + parts);
            }
        }
    }

    // ---------- 9. czas i pamięć ----------

    static void speed() {
        Random r = new Random(99);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 600_000) sb.append(structured(r, 30)).append("\n\n");
        String big = sb.toString();
        for (int limit : new int[]{10, 450, 5000}) {
            long t0 = System.nanoTime();
            List<String> parts = smart(big, limit);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            properties(big, limit, parts, "duży tekst (limit " + limit + ")");
            ok(ms < 8000, "dzielenie " + big.length() + " znaków przy limicie " + limit + " trwało " + ms + " ms");
            System.out.println("  " + big.length() + " znaków, limit " + limit + ": " + parts.size() + " części, " + ms + " ms");
        }
        // bardzo gęsty tekst: same jednoznakowe słowa, okno ma tysiące miejsc na cięcie
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < 400_000; i++) dense.append(i % 7 == 6 ? "\n" : "a ");
        long t0 = System.nanoTime();
        List<String> parts = smart(dense.toString(), 5000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        properties(dense.toString(), 5000, parts, "gęsty tekst");
        ok(ms < 8000, "gęsty tekst trwał " + ms + " ms");
        System.out.println("  gęsty tekst: " + dense.length() + " znaków, " + parts.size() + " części, " + ms + " ms");

        // tekst dłuższy niż MAX_CHARS: zwykły algorytm (dzielenie inteligentne zjadłoby za dużo pamięci)
        StringBuilder huge = new StringBuilder();
        while (huge.length() <= SmartSplitter.MAX_CHARS) huge.append(VOCAB).append("\n\n");
        String over = huge.toString();
        eq(Splitter.split(over, 450), smart(over, 450), "tekst dłuższy niż MAX_CHARS dzielę zwykłym algorytmem");
        ok(!Splitter.split(over, 450).equals(SmartSplitter.split(norm(over), 450)), "na tej liście oba sposoby dają różne części (inaczej test nic nie mówi)");
        // dokładnie MAX_CHARS znaków: jeszcze inteligentnie
        String exact = over.substring(0, SmartSplitter.MAX_CHARS);
        List<String> ex = smart(exact, 450);
        properties(exact, 450, ex, "MAX_CHARS");
        eq(SmartSplitter.split(norm(exact), 450), ex, "dokładnie MAX_CHARS znaków dzielę jeszcze inteligentnie");
        ok(!Splitter.split(exact, 450).equals(ex), "na tej liście oba sposoby dają różne części");
    }

    public static void main(String[] args) {
        basics();
        costTable();
        tradeOffs();
        longTokens();
        headingsAndMarkers();
        vocabulary();
        planAgainstReferences();
        classicOnProse();
        sectionsStayWhole();
        speed();
        System.out.println("podział inteligentny: OK (" + randomTexts + " losowych tekstów, " + bruteChecked + " sprawdzonych przeglądem wyczerpującym, "
                + proseChecked + " tekstów prozą porównanych ze zwykłym dzieleniem, " + sectionsChecked + " sekcji mieszczących się w limicie, "
                + chunksSeen + " części)");
        System.out.println("razem sprawdzeń: " + checks);
    }
}
