package pl.dzielnik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.util.List;

/** Kanały powiadomień i wysyłanie powiadomień (zwykłych i z przyciskiem „Odpowiedz”). */
final class Notifier {

    static final String CHANNEL_PARTS = "czesci";
    static final String CHANNEL_PROGRESS = "postep";
    static final String CHANNEL_ASK = "pytanie";
    static final String CHANNEL_LISTEN = "nasluch";

    static final int ID_PROGRESS = 1;
    static final int ID_TEST = 2;
    static final int ID_ASK = 3;
    static final int ID_GEMINI_PROGRESS = 4;
    static final int ID_ERROR = 5;
    static final int ID_LISTEN = 6;
    static final int ID_PART_BASE = 1000;
    static final int ID_GEMINI_BASE = 100000;
    static final int ID_GEMINI_END = 200000;

    /** Rodzaj powiadomienia: od niego zależy, co zrobi „Odpowiedz”. */
    static final String KIND_PART = "czesc";
    static final String KIND_GEMINI = "gemini";
    static final String KIND_ASK = "pytanie";

    /** Android pozwala jednej aplikacji mieć naraz 50 powiadomień. Zostawiam zapas na postęp i test. */
    private static final int MAX_ACTIVE = 45;

    private Notifier() {}

    /** Pozwala przerwać wysyłanie kolejnych części i zgłasza postęp. */
    interface Control {
        boolean stopped();

        void progress(int done, int total, String info);
    }

    static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = manager(c);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PARTS, "Części tekstu i odpowiedzi", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PROGRESS, "Postęp wysyłania", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ASK, "Pole pytania do Gemini", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_LISTEN, "Nasłuchiwanie Telegrama", NotificationManager.IMPORTANCE_LOW));
    }

    /** Powiadomienie bez przycisku „Odpowiedz”. */
    static void post(Context c, int id, String title, String text) {
        post(c, id, title, text, null, 0, false);
    }

    /**
     * Powiadomienie z jedną częścią tekstu. Treść jest i w widoku zwiniętym, i w rozwiniętym.
     *
     * @param kind  rodzaj powiadomienia; gdy nie jest null i Gemini jest skonfigurowane, dostaje przycisk „Odpowiedz”
     * @param when  czas, pod którym powiadomienie ma stać w panelu (0 = teraz); przy ponownym wysłaniu
     *              zachowuje dawne miejsce
     * @param quiet true = bez dźwięku, gdy powiadomienie jest tylko odświeżane
     */
    static void post(Context c, int id, String title, String text, String kind, long when, boolean quiet) {
        ensureChannels(c);
        long w = when > 0 ? when : System.currentTimeMillis();
        Notification.Builder b = new Notification.Builder(c, CHANNEL_PARTS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setWhen(w)
                .setOnlyAlertOnce(quiet);
        if (kind != null && Prefs.replyEnabled(c)) {
            b.addAction(replyAction(c, id, title, text, kind, w));
        }
        manager(c).notify(id, b.build());
    }

    /** Powiadomienie z samym polem pytania do Gemini (punkt startu rozmowy z panelu powiadomień). */
    static void postAsk(Context c, boolean quiet) {
        ensureChannels(c);
        long w = System.currentTimeMillis();
        String text = "Naciśnij „Odpowiedz”, wpisz pytanie i wyślij. Odpowiedź Gemini przyjdzie jako kolejne powiadomienie.";
        Notification n = new Notification.Builder(c, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle("Zapytaj Gemini")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setWhen(w)
                .setOnlyAlertOnce(quiet)
                .addAction(replyAction(c, ID_ASK, "Zapytaj Gemini", "", KIND_ASK, w))
                .build();
        manager(c).notify(ID_ASK, n);
    }

    static void postError(Context c, String text) {
        postError(c, "Gemini: coś poszło nie tak", text);
    }

    static void postError(Context c, String title, String text) {
        ensureChannels(c);
        Notification n = new Notification.Builder(c, CHANNEL_PARTS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(c.getColor(R.color.danger))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        manager(c).notify(ID_ERROR, n);
    }

    /** Usuwa własne powiadomienia o numerach z podanego przedziału. */
    static void cancelRange(Context c, int fromInclusive, int toExclusive) {
        NotificationManager nm = manager(c);
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            int id = sbn.getId();
            if (id >= fromInclusive && id < toExclusive) nm.cancel(id);
        }
    }

    /**
     * Wysyła części jako osobne powiadomienia, z przerwą między nimi.
     * Przy odwróconej kolejności zaczyna od ostatniej części, więc część 1 przychodzi na końcu
     * i w panelu, gdzie najnowsze są na górze, jest pierwsza od góry.
     *
     * @param numberSingle czy pojedyncza część też ma numer w tytule (np. „Część 1/1”)
     */
    static void postParts(Context c, List<String> parts, String prefix, boolean numberSingle, int idBase,
                          String kind, long delayMs, boolean reverse, Control ctl) throws InterruptedException {
        NotificationManager nm = manager(c);
        int total = parts.size();
        for (int k = 0; k < total && !ctl.stopped(); k++) {
            int i = reverse ? total - 1 - k : k;

            // Przy limicie 50 powiadomień czekam, aż użytkownik część z nich usunie.
            while (!ctl.stopped() && nm.getActiveNotifications().length >= MAX_ACTIVE) {
                ctl.progress(k, total, "Czekam, aż usuniesz część powiadomień");
                Thread.sleep(1500);
            }
            if (ctl.stopped()) break;

            String title = total == 1 && !numberSingle ? prefix : prefix + " " + (i + 1) + "/" + total;
            post(c, idBase + i, title, parts.get(i), kind, 0, false);
            ctl.progress(k + 1, total, null);
            if (k + 1 < total) Thread.sleep(delayMs);
        }
    }

    /** Powiadomienie testowe do mierzenia długości. */
    static void postTest(Context c, int length) {
        post(c, ID_TEST, "Test długości (" + length + " zn.)", Measure.generate(length));
    }

    static String wiadomosci(int n) {
        return n == 1 ? "wiadomość" : "wiadomości";
    }

    static String powiadomien(int n) {
        if (n == 1) return "powiadomienie";
        int last = n % 10;
        int last2 = n % 100;
        if (last >= 2 && last <= 4 && !(last2 >= 12 && last2 <= 14)) return "powiadomienia";
        return "powiadomień";
    }

    /**
     * Przycisk „Odpowiedz” z polem tekstowym. Odpowiedź trafia do GeminiService razem z treścią
     * powiadomienia, z którego pochodzi. Kolejne wywołanie dla tego samego numeru odświeża dane w przycisku.
     */
    private static Notification.Action replyAction(Context c, int id, String title, String text, String kind, long when) {
        RemoteInput input = new RemoteInput.Builder(GeminiService.KEY_REPLY)
                .setLabel("Pytanie do Gemini")
                .build();
        Intent intent = new Intent(c, GeminiService.class)
                .setAction(GeminiService.ACTION_ASK)
                .putExtra(GeminiService.EXTRA_ID, id)
                .putExtra(GeminiService.EXTRA_TITLE, title)
                .putExtra(GeminiService.EXTRA_TEXT, text)
                .putExtra(GeminiService.EXTRA_KIND, kind)
                .putExtra(GeminiService.EXTRA_WHEN, when);
        // Pole odpowiedzi wpisuje wynik do intencji, więc ta intencja musi być zmienna (od Androida 12).
        // Numer id jako kod żądania: każde powiadomienie ma własną intencję z własną treścią.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getForegroundService(c, id, intent, flags);
        return new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_notif), "Odpowiedz", pi)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build();
    }
}
