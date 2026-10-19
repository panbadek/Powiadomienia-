# Dzielnik

Wklejasz tekst, aplikacja tnie go na części o zadanej długości (w znakach) i wysyła każdą część jako osobne powiadomienie.

## Jak działa podział

- Cięcie następuje tylko między zdaniami. Zdanie kończy się kropką (też `!` i `?`), a następne zaczyna się od wielkiej litery. Dlatego „np. jabłko” nie kończy zdania, ale „np. Kowalski” już tak.
- Zdanie, które nie mieści się w bieżącej części, przechodzi w całości do następnej. Tak aż do końca tekstu.
- Zdanie dłuższe niż cały limit dzielę po słowach, żeby cały tekst został wysłany.
- Między powiadomieniami jest przerwa (domyślnie 2 s). Android pozwala aplikacji mieć naraz 50 powiadomień, więc przy tym limicie wysyłanie czeka, aż usuniesz część.
- Tekst możesz też wysłać do Dzielnika z innej aplikacji: Udostępnij → Dzielnik.

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

Przy aktualizacji najpierw odinstaluj starą wersję, bo każda budowa jest podpisana innym kluczem.

**Przez Android Studio**
File → Open → folder `Dzielnik`, poczekaj na synchronizację, potem Build → Build APK(s) albo Run z podłączonym telefonem.

## Testy logiki

Podział i pomiar nie zależą od Androida i mają test (zwykła Java 17+):

```
javac -encoding UTF-8 -d out app/src/main/java/pl/dzielnik/Splitter.java app/src/main/java/pl/dzielnik/Measure.java tests/SplitterTest.java
java -cp out SplitterTest
```

## Wskazówka

Jeśli czytasz powiadomienia na zegarku, włącz przekazywanie powiadomień dla Dzielnika w aplikacji zegarka.
