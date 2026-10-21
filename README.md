# Powiadomienia

Wklejasz tekst, aplikacja tnie go na części o zadanej długości (w znakach) i wysyła każdą część jako osobne powiadomienie. Części i odpowiedzi Gemini mogą też iść jako wiadomości od Twojego bota w Telegramie, dzięki czemu da się na nie odpowiadać z zegarka.

## Jak działa podział

- Cięcie następuje tylko między zdaniami. Zdanie kończy się kropką (też `!` i `?`), a następne zaczyna się od wielkiej litery. Dlatego „np. jabłko” nie kończy zdania, ale „np. Kowalski” już tak.
- Zdanie, które nie mieści się w bieżącej części, przechodzi w całości do następnej. Tak aż do końca tekstu.
- Lista, nagłówki i wiersze wierszy zwykle nie mają kropek, więc cały taki tekst jest jednym „zdaniem”. Gdy jest dłuższy niż limit, dzielę go po wierszach: każdy wiersz trafia w całości do jednej części, a kolejne wiersze są upychane, dopóki się mieszczą. Lista „Maroko – Rabat / Algieria – Algier / …” nie zostanie więc przecięta w środku wpisu.
- Wiersz (albo zdanie bez końców wierszy) dłuższy niż cały limit dzielę po słowach, żeby cały tekst został wysłany.
- Spacja albo koniec wiersza na granicy cięcia nie jest wysyłany, więc suma długości części jest o kilka znaków mniejsza niż długość całego tekstu.
- Między powiadomieniami jest przerwa (domyślnie 2 s). Android pozwala aplikacji mieć naraz 50 powiadomień, więc przy tym limicie wysyłanie czeka, aż usuniesz część.
- Tekst możesz też wysłać do aplikacji z innej aplikacji: Udostępnij → Powiadomienia.
- Opcja „Wysyłaj od ostatniej części do pierwszej”: najnowsze powiadomienie jest w panelu na samej górze, więc przy tej opcji część 1 przychodzi ostatnia i jest pierwsza od góry. Numery w tytułach („Część 3/12”) się nie zmieniają.

## Gemini: odpowiadanie z powiadomień

1. Weź klucz API w Google AI Studio i wklej go w aplikacji, w karcie „Gemini w powiadomieniach”. Klucz zostaje tylko w telefonie i jest wysyłany wyłącznie do Google.
2. Od tej chwili powiadomienia z tej aplikacji mają przycisk „Odpowiedz”. Do Gemini idzie Twoja odpowiedź razem z treścią powiadomienia, na które odpowiadasz, a odpowiedź Gemini wraca jako kolejne powiadomienia. Dzielę ją tak samo jak wklejony tekst (długość części, przerwa i kolejność z ustawień). One też mają przycisk „Odpowiedz”, więc rozmowę ciągniesz prosto z panelu powiadomień.
3. Nie masz jeszcze żadnego powiadomienia? Naciśnij „Wstaw do powiadomień pole pytania” albo wpisz pytanie w aplikacji i naciśnij „Zapytaj Gemini”.

Szczegóły:
- Gemini pamięta ostatnie 10 wymian pytanie-odpowiedź. „Zacznij nową rozmowę” czyści tę pamięć.
- Nowa odpowiedź Gemini zastępuje w panelu poprzednią. Części wklejonego tekstu zostają.
- Domyślny model to `gemini-3.5-flash-lite` (największy darmowy limit dzienny). Inny wpiszesz w polu „Model”, np. `gemini-3.8-flash`. Aktualne limity darmowego planu sprawdzisz w Google AI Studio.
- Gemini dostaje polecenie, żeby pisać zwykłym tekstem i pełnymi zdaniami, dzięki czemu cięcie trafia między zdania.
- Błędy (zły klucz, limit zapytań, brak internetu) przychodzą jako powiadomienie „Gemini: coś poszło nie tak”.
- Pytania i odpowiedzi idą przez internet do Google, więc nie wysyłaj tak niczego, czego nie chcesz tam wysyłać.
- Czy przycisk „Odpowiedz” pokaże się też na zegarku, zależy od aplikacji zegarka. Zegarki Huawei pozwalają odpowiadać tylko na powiadomienia z wybranych komunikatorów (m.in. Telegrama), więc na nich powiadomienia tej aplikacji najpewniej przycisku nie dostaną. Do odpowiadania z zegarka służy następna sekcja.

## Odpowiadanie z zegarka (przez Telegram)

Zegarek Huawei pokazuje pole odpowiedzi tylko przy powiadomieniach z komunikatorów z listy producenta (m.in. Telegram, WhatsApp, Messenger). Dlatego aplikacja umie pisać do Ciebie przez **własnego bota w Telegramie**. Na zegarku widzisz wiadomość od bota, odpowiadasz na niej, aplikacja na telefonie odbiera odpowiedź, pyta Gemini i odsyła odpowiedź jako kolejną wiadomość.

```
zegarek: odpowiedź  ->  Telegram  ->  Powiadomienia w telefonie  ->  Gemini
zegarek: odpowiedź Gemini  <-  Telegram  <-  Powiadomienia w telefonie
```

**Ustawienie (jednorazowo)**
1. W Telegramie otwórz @BotFather, wyślij `/newbot`, wybierz nazwę i skopiuj token (wygląda jak `123456789:ABC…`).
2. W aplikacji, w karcie „Odpowiadanie z zegarka (Telegram)”, wklej token. Potem w Telegramie otwórz swojego bota, napisz mu `/start` i w aplikacji naciśnij „Połącz z botem”. Aplikacja znajdzie Twój czat, zapamięta go i napisze powitanie.
3. Wpisz klucz Gemini (karta „Gemini w powiadomieniach”) i włącz „Nasłuchuj pytań z Telegrama”. W pasku powiadomień pojawi się stałe powiadomienie „Nasłuch Telegrama włączony”.
4. W „Gdzie wysyłać części tekstu i odpowiedzi Gemini” wybierz „Telegram” (albo „Oba”, jeśli chcesz też zwykłe powiadomienia w telefonie).
5. Przyciskiem „Bateria: pozwól na pracę w tle” zwolnij aplikację z oszczędzania baterii. Bez tego producent telefonu może wyłączyć nasłuch, gdy ekran jest wygaszony.
6. W aplikacji Huawei Zdrowie: Urządzenia → Twój zegarek → Powiadomienia. Włącz przekazywanie powiadomień i zaznacz Telegram na liście aplikacji. Włącz też „Szybkie odpowiedzi” (nazwa zależy od wersji aplikacji). W samym Telegramie zostaw powiadomienia z podglądem wiadomości, bo bez treści w powiadomieniu zegarek nic nie pokaże.
7. Naciśnij „Wyślij wiadomość testową do Telegrama”. Wiadomość ma pojawić się na zegarku. Odpowiedz na niej i sprawdź, czy po chwili przyjdzie odpowiedź Gemini.

**Jak to działa**
- Aplikacja obsługuje tylko Twój prywatny czat z botem. Wiadomości od innych osób i z grup ignoruje, więc nikt obcy nie zada pytania Twoim kluczem Gemini, nawet jeśli pozna nazwę bota.
- Gdy odpowiadasz na konkretną wiadomość od bota, jej treść idzie do Gemini razem z Twoją odpowiedzią (tak samo jak przy powiadomieniach). Jeśli zegarek wysyła zwykłą wiadomość bez cytatu, do Gemini idzie sama odpowiedź, a pamięć rozmowy i tak pamięta wcześniejsze wymiany.
- Na zegarku odpowiadasz klawiaturą ekranową albo gotową szybką odpowiedzią. Według opisu Huaweia tekst własny jest dostępny tylko na niektórych modelach, a według serwisu LOS40 GT 5 ma i klawiaturę, i szybkie odpowiedzi. Gdy Twój zegarek ma tylko szybkie odpowiedzi, ustaw w Huawei Zdrowie własne krótkie polecenia, np. „Dalej”, „Krócej”, „Wyjaśnij prościej”. Aplikacja wysyła taką odpowiedź do Gemini jak każde pytanie, a Gemini pamięta rozmowę, więc „Dalej” ma sens.
- Komendy w czacie z botem: `/nowa` zaczyna nową rozmowę (czyści pamięć Gemini), `/start` pokazuje krótką pomoc.
- Odpowiedź na pytanie zadane z Telegrama wraca do Telegrama. Wybór „Oba” dodaje do tego zwykłe powiadomienia w telefonie. Wklejony w aplikacji tekst i pytania z aplikacji lub powiadomień idą tam, gdzie wskażesz w ustawieniu „Gdzie wysyłać”. Gdy Telegram odmówi (np. brak sieci), odpowiedź trafia do powiadomień, żeby nie przepadła.
- Długość części, przerwa i kolejność (także „od ostatniej części”) działają tak samo jak przy powiadomieniach. Telegram pozwala mniej więcej na jedną wiadomość na sekundę, więc przerwę ustaw na co najmniej 1 s. Część dłuższą niż 3900 znaków aplikacja dzieli dalej.
- Gdy telefon był bez internetu albo nasłuch był wyłączony, wiadomości czekają w Telegramie (do 24 godzin) i aplikacja obsłuży je po powrocie sieci.
- Błędy Gemini (zły klucz, limit zapytań) wracają do Ciebie też w Telegramie, żebyś nie czekał na zegarku na odpowiedź, która nie przyjdzie.

**Czego nie sprawdziłem i na co uważać**
- Nie miałem tu prawdziwego telefonu, zegarka ani Telegrama. Cały obieg (odbiór wiadomości, pytanie do Gemini, odpowiedź, błędy, zatrzymanie i ponowny start nasłuchu) sprawdziłem na zwykłej Javie ze sztucznymi serwerami Telegrama i Gemini oraz na atrapach Androida. Nie wiem, czy Twój model zegarka z Twoim oprogramowaniem pokaże przy wiadomości z Telegrama przycisk odpowiedzi ani czy wyśle ją jako cytat. To zależy od Huaweia i trzeba sprawdzić na zegarku.
- Huawei wymaga, żeby powiadomienie z Telegrama nadal istniało w telefonie. Jeśli je usuniesz (albo Telegram je zwinie), odpowiedź z zegarka może zakończyć się błędem „Failed to send”. Odpowiadaj, zanim wyczyścisz panel powiadomień.
- Wiadomości przechodzą przez serwery Telegrama. Rozmowy z botami nie są szyfrowane od końca do końca, więc nie wysyłaj tak niczego poufnego. Token bota daje pełną kontrolę nad botem. Nie udostępniaj go, a po wycieku wygeneruj nowy w @BotFather (`/revoke`).
- Nasłuch nie włącza się sam po ponownym uruchomieniu telefonu. Otwórz aplikację raz, a uruchomi go znowu. Tak samo po zatrzymaniu aplikacji siłą albo po tym, jak system wyłączy usługę po wielu godzinach pracy w tle: wystarczy wejść do aplikacji.
- Z jednego tokenu może odbierać wiadomości tylko jeden program naraz. Jeśli ten sam bot jest podłączony gdzie indziej (inna aplikacja, skrypt, webhook), Telegram odrzuca zapytania (błąd 409), a aplikacja pokaże to w karcie Telegrama.
- Chcesz najpierw sprawdzić, czy zegarek w ogóle pokazuje odpowiedź przy powiadomieniach tej aplikacji? Naciśnij „Wstaw do powiadomień pole pytania” i zajrzyj na zegarek. Jeśli przycisk „Odpowiedz” tam jest, Telegram nie jest potrzebny.

## Pomiar długości powiadomienia

„Wyślij test” wysyła powiadomienie ze specjalnym tekstem:

```
12345678;10;345678;20;345678;30;345678; ... ;100;45678;110;45678; ...
```

Każdy znak to jedność numeru swojej pozycji (liczonej od 1). Co 10 znaków stoi pełna liczba dziesiątek, ustawiona tak, że zaczyna się dokładnie na swojej pozycji. Po liczbie jest `;`, a potem jedności od pierwszej wolnej pozycji (od 3 po liczbach dwucyfrowych, od 4 po trzycyfrowych itd.).

Długość odczytujesz tak: **N + liczba cyfr N + liczba znaków po `;` za N**. Przykład: końcówka `;120;45` to 120 + 3 + 2 = **125 znaków**.

Rozwiń powiadomienie, przepisz końcówkę razem ze średnikiem przed ostatnią liczbą do pola „Końcówka, którą widzisz” i naciśnij „Oblicz długość”. Przyciskiem „Ustaw długość części” przenosisz wynik do ustawień.

## Jak zdobyć aplikację (APK)

**Przez GitHub, bez Android Studio**
1. Załóż repozytorium na GitHubie i wgraj do niego całą zawartość rozpakowanego folderu projektu, razem z ukrytym folderem `.github`.
2. Wejdź w zakładkę Actions i poczekaj, aż „Zbuduj APK” zrobi się zielone (kilka minut).
3. Na stronie repozytorium otwórz Releases → „Powiadomienia (najnowsza wersja)” i pobierz **jeden plik: `Powiadomienia.apk`**. Zainstaluj go na telefonie (Android poprosi o zgodę na instalację z nieznanych źródeł).

Wersje do 1.0 były podpisane innym kluczem w każdej budowie, więc przy przejściu na 1.1 odinstaluj starą wersję. Od 1.1 (czyli także 1.2 z Telegramem i 1.3) aplikacja jest podpisana stałym, publicznym kluczem testowym z pliku `app/debug.keystore` (tylko do użytku osobistego), więc kolejne wersje instalują się na wierzchu, a ustawienia, w tym klucz Gemini i token bota, zostają.

**Przez Android Studio**
File → Open → folder projektu, poczekaj na synchronizację, potem Build → Build APK(s) albo Run z podłączonym telefonem.

## Testy logiki

Podział, pomiar, rozmowa z Gemini (JSON, zapytanie, odczyt odpowiedzi i błędów, ponawianie, pamięć rozmowy) oraz klient Telegrama i pętla nasłuchu nie zależą od Androida i mają testy (zwykła Java 17+). Testy Gemini i Telegrama same uruchamiają sztuczny serwer HTTP:

```
javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Splitter.java app/src/main/java/pl/dzielnik/Measure.java tests/SplitterTest.java
java -cp out SplitterTest

javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Json.java app/src/main/java/pl/dzielnik/Gemini.java app/src/main/java/pl/dzielnik/History.java tests/pl/dzielnik/GeminiTest.java
java -cp out pl.dzielnik.GeminiTest

javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Json.java app/src/main/java/pl/dzielnik/Splitter.java app/src/main/java/pl/dzielnik/Measure.java app/src/main/java/pl/dzielnik/Telegram.java app/src/main/java/pl/dzielnik/TelegramBridge.java app/src/main/java/pl/dzielnik/Dest.java tests/pl/dzielnik/TelegramTest.java
java -cp out pl.dzielnik.TelegramTest
```

## Wskazówka

Jeśli czytasz powiadomienia z tej aplikacji na zegarku, włącz przekazywanie powiadomień dla „Powiadomienia” w aplikacji zegarka. Odpowiadać z zegarka da się przez Telegram (sekcja wyżej).
