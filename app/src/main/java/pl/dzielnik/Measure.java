package pl.dzielnik;

/**
 * Tekst do mierzenia długości powiadomienia oraz odczyt wyniku.
 *
 * Każdy znak testowego tekstu to jedność numeru swojej pozycji (liczonej od 1),
 * a co 10 znaków stoi pełna liczba dziesiątek tak, że zaczyna się dokładnie na swojej pozycji:
 *
 *   12345678;10;345678;20;345678;30;345678; ... ;100;45678;110;45678; ...
 *
 * Liczba N zajmuje pozycje N, N+1, ...; po niej jest ";", a potem jedności od pozycji N+(cyfry N)+1
 * aż do ósemki. Pozycja N+9 to znów ";".
 *
 * Długość widocznego fragmentu = N + (liczba cyfr N) + (liczba znaków po ";" za N).
 * Np. końcówka ";120;45" daje 120 + 3 + 2 = 125.
 */
public final class Measure {

    /** Android i tak obcina tekst powiadomienia do ok. 5000 znaków. */
    public static final int MAX = 5000;

    private static final String FIRST = "12345678";

    private Measure() {}

    public static String generate(int length) {
        if (length < 0) length = 0;
        StringBuilder sb = new StringBuilder(length + 16);
        sb.append(FIRST).append(';');
        for (int n = 10; sb.length() < length; n += 10) {
            String num = Integer.toString(n);
            sb.append(num).append(';');
            for (int pos = n + num.length() + 1; pos <= n + 8; pos++) {
                sb.append((char) ('0' + pos % 10));
            }
            sb.append(';');
        }
        sb.setLength(Math.min(sb.length(), length));
        return sb.toString();
    }

    /**
     * Odczytuje długość z końcówki, którą widać w powiadomieniu (np. ";120;45").
     * Zwraca -1, gdy zapis nie pasuje do wzoru albo brakuje ostatniej liczby dziesiątek.
     */
    public static int decode(String raw) {
        if (raw == null) return -1;
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || c == '.' || c == '…') continue;
            cleaned.append(c);
        }
        String s = cleaned.toString();
        if (s.isEmpty()) return -1;

        int len;
        if (s.indexOf(';') < 0) {
            len = FIRST.startsWith(s) ? s.length() : -1;
        } else {
            String[] parts = s.split(";", -1);
            int last = parts.length - 1;
            String t = parts[last];      // znaki za ostatnim ";"
            String p = parts[last - 1];  // to, co stoi tuż przed ostatnim ";"
            // Liczba musi mieć przed sobą ";" - inaczej mogłaby być tylko końcem dłuższej liczby (np. 10 zamiast 110).
            if (last >= 2 && isTens(p)) {
                // ostatni ";" stoi za liczbą dziesiątek
                len = Integer.parseInt(p) + p.length() + t.length();
            } else if (p.endsWith("8")) {
                // ostatni ";" zamyka ciąg jedności (pozycja N+9), N to liczba przed nim
                if (last >= 3 && isTens(parts[last - 2])) {
                    len = Integer.parseInt(parts[last - 2]) + 9 + t.length();
                } else if (last == 1 && p.equals(FIRST)) {
                    len = 9 + t.length();
                } else {
                    return -1;
                }
            } else {
                return -1;
            }
        }
        if (len < s.length() || len > 100000) return -1;
        return generate(len).endsWith(s) ? len : -1;
    }

    private static boolean isTens(String p) {
        if (p.length() < 2 || p.length() > 7 || !p.endsWith("0") || p.charAt(0) == '0') return false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
