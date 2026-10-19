# Dzielnik

Wklejasz tekst, aplikacja tnie go na części o zadanej długości (w znakach) i wysyła każdą część jako osobne powiadomienie.

## Jak działa podział

- Cięcie następuje tylko między zdaniami. Zdanie kończy się kropką (też `!` i `?`), a następne zaczyna się od wielkiej litery. Dlatego „np. jabłko” nie kończy zdania, ale „np. Kowalski” już tak.
- Zdanie, które nie mieści się w bieżącej części, przechodzi w całości do następnej. Tak aż do końca tekstu.
- Zdanie dłuższe niż cały limit dzielę po słowach, żeby cały tekst został wysłany.
- Między powiadomieniami jest przerwa (domyślnie 2 s). Android pozwala aplikacji mieć naraz 50 powiadomień, więc przy tym limicie wysyłanie czeka, aż usuniesz część.
- Tekst możesz też wysłać do Dzielnika z innej aplikacji: Udostępnij → Dzielnik.
- Opcja „Wysyłaj od ostatniej części do pierwszej”: najnowsze powiadomienie jest w panelu na samej górze, więc przy tej opcji część 1 przychodzi ostatnia i jest pierwsza od góry. Numery w tytułach („Część 3/12”) się nie zmieniają.

## Gemini: odpowiadanie z powiadomień

1. Weź klucz API w Google AI Studio i wklej go w aplikacji, w karcie „Gemini w powiadomieniach”. Klucz zostaje tylko w telefonie i jest wysyłany wyłącznie do Google.
2. Od tej chwili powiadomienia z Dzielnika mają przycisk „Odpowiedz”. Do Gemini idzie Twoja odpowiedź razem z treścią powiadomienia, na które odpowiadasz, a odpowiedź Gemini wraca jako kolejne powiadomienia. Dzielę ją tak samo jak wklejony tekst (długość części, przerwa i kolejność z ustawień). One też mają przycisk „Odpowiedz”, więc rozmowę ciągniesz prosto z panelu powiadomień.
3. Nie masz jeszcze żadnego powiadomienia? Naciśnij „Wstaw do powiadomień pole pytania” albo wpisz pytanie w aplikacji i naciśnij „Zapytaj Gemini”.

Szczegóły:
- Gemini pamięta ostatnie 10 wymian pytanie-odpowiedź. „Zacznij nową rozmowę” czyści tę pamięć.
- Nowa odpowiedź Gemini zastępuje w panelu poprzednią. Części wklejonego tekstu zostają.
- Domyślny model to `gemini-3.5-flash-lite` (największy darmowy limit dzienny). Inny wpiszesz w polu „Model”, np. `gemini-3.8-flash`. Aktualne limity darmowego planu sprawdzisz w Google AI Studio.
- Gemini dostaje polecenie, żeby pisać zwykłym tekstem i pełnymi zdaniami, dzięki czemu cięcie trafia między zdania.
- Błędy (zły klucz, limit zapytań, brak internetu) przychodzą jako powiadomienie „Gemini: coś poszło nie tak”.
- Pytania i odpowiedzi idą przez internet do Google, więc nie wysyłaj tak niczego, czego nie chcesz tam wysyłać.
- Czy przycisk „Odpowiedz” pokaże się też na zegarku, zależy od aplikacji zegarka. Tego nie sprawdzałem.

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
1. Załóż repozytorium na GitHubie i wgraj do niego całą zawartość rozpakowanego folderu `Dzielnik`, razem z ukrytym folderem `.github`.
2. Wejdź w zakładkę Actions i poczekaj, aż „Zbuduj APK” zrobi się zielone (kilka minut).
3. Na stronie repozytorium otwórz Releases → „Dzielnik (najnowsza wersja)” i pobierz **jeden plik: `Dzielnik.apk`**. Zainstaluj go na telefonie (Android poprosi o zgodę na instalację z nieznanych źródeł).

Wersje do 1.0 były podpisane innym kluczem w każdej budowie, więc przy przejściu na 1.1 odinstaluj starą wersję. Od 1.1 aplikacja jest podpisana stałym, publicznym kluczem testowym z pliku `app/debug.keystore` (tylko do użytku osobistego), więc kolejne wersje instalują się na wierzchu, a ustawienia, w tym klucz Gemini, zostają.

**Przez Android Studio**
File → Open → folder `Dzielnik`, poczekaj na synchronizację, potem Build → Build APK(s) albo Run z podłączonym telefonem.

## Testy logiki

Podział, pomiar i rozmowa z Gemini (JSON, zapytanie, odczyt odpowiedzi i błędów, ponawianie, pamięć rozmowy) nie zależą od Androida i mają testy (zwykła Java 17+). Test Gemini sam uruchamia sztuczny serwer HTTP:

```
javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Splitter.java app/src/main/java/pl/dzielnik/Measure.java tests/SplitterTest.java
java -cp out SplitterTest

javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Json.java app/src/main/java/pl/dzielnik/Gemini.java app/src/main/java/pl/dzielnik/History.java tests/pl/dzielnik/GeminiTest.java
java -cp out pl.dzielnik.GeminiTest
```

## Wskazówka

Jeśli czytasz powiadomienia na zegarku, włącz przekazywanie powiadomień dla Dzielnika w aplikacji zegarka.
