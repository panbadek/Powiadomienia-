package pl.dzielnik;

import android.app.Notification;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Zadaje pytania Gemini: z pola w aplikacji albo z przycisku „Odpowiedz” w powiadomieniu.
 * Odpowiedź dzieli tak samo jak wklejony tekst i wysyła jako kolejne powiadomienia.
 * Działa jako usługa pierwszoplanowa, więc kończy pracę też po wyjściu z aplikacji.
 */
public class GeminiService extends Service {

    static final String ACTION_ASK = "pl.dzielnik.ASK";
    /** Klucz pola odpowiedzi w powiadomieniu (RemoteInput). */
    static final String KEY_REPLY = "odpowiedz";
    static final String EXTRA_QUESTION = "pytanie";
    static final String EXTRA_ID = "id";
    static final String EXTRA_TITLE = "tytul";
    static final String EXTRA_TEXT = "tresc";
    static final String EXTRA_KIND = "rodzaj";
    static final String EXTRA_WHEN = "kiedy";

    private static final int MAX_QUESTION = 8000;

    /** Ostatni komunikat o pytaniu, pokazywany na ekranie aplikacji. */
    static volatile String status = "";

    /** Jedna rozmowa: pytania obsługuję po kolei, żeby pamięć rozmowy się nie rozjechała. */
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dzielnik-gemini");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger waiting = new AtomicInteger();
    private volatile int lastStartId;

    private static final class Job {
        final String question;
        final String kind;
        final int id;
        final String title;
        final String text;
        final long when;

        Job(String question, String kind, int id, String title, String text, long when) {
            this.question = question;
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.text = text;
            this.when = when;
        }
    }

    /** Pytanie wpisane w aplikacji (bez powiadomienia, na które się odpowiada). */
    static boolean start(Context c, String question) {
        try {
            c.startForegroundService(new Intent(c, GeminiService.class)
                    .setAction(ACTION_ASK)
                    .putExtra(EXTRA_QUESTION, question));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        Notifier.ensureChannels(this);
        try {
            Notification n = progressNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_GEMINI_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifier.ID_GEMINI_PROGRESS, n);
            }
        } catch (RuntimeException ignored) {
            // bez powiadomienia usługi system szybciej zabije proces, ale pytanie spróbuję wysłać mimo to
        }

        final Job job = readJob(intent);
        if (job == null) {
            if (waiting.get() == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        status = "Pytam Gemini…";
        waiting.incrementAndGet();
        QUEUE.execute(() -> {
            try {
                handle(job);
            } catch (RuntimeException e) {
                fail("Nieoczekiwany błąd: " + e);
            } finally {
                if (waiting.decrementAndGet() == 0) stopSelf(lastStartId);
            }
        });
        return START_NOT_STICKY;
    }

    private Job readJob(Intent intent) {
        if (intent == null) return null;
        String question = null;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results != null) {
            CharSequence reply = results.getCharSequence(KEY_REPLY);
            if (reply != null) question = reply.toString();
        }
        if (question == null) question = intent.getStringExtra(EXTRA_QUESTION);
        if (question == null) return null;
        question = question.trim();
        if (question.isEmpty()) return null;
        if (question.length() > MAX_QUESTION) question = question.substring(0, MAX_QUESTION);

        String text = intent.getStringExtra(EXTRA_TEXT);
        return new Job(question, intent.getStringExtra(EXTRA_KIND), intent.getIntExtra(EXTRA_ID, 0),
                intent.getStringExtra(EXTRA_TITLE), text == null ? "" : text, intent.getLongExtra(EXTRA_WHEN, 0));
    }

    private void handle(Job job) {
        refreshReplied(job);

        String key = Prefs.geminiKey(this);
        if (key.isEmpty()) {
            fail("Brak klucza Gemini. Wpisz go w aplikacji Dzielnik, w sekcji Gemini.");
            return;
        }

        // Treść powiadomienia, na które odpowiadam, idzie do Gemini razem z moją odpowiedzią.
        String userText = Gemini.compose(job.text, job.question);
        List<Gemini.Turn> history = History.parse(Prefs.history(this));
        Gemini.Result result = Gemini.ask(Gemini.DEFAULT_BASE, key, Prefs.geminiModel(this), Gemini.SYSTEM,
                history, userText, 20000, 120000, new int[]{3000, 8000});
        if (!result.ok()) {
            fail(result.error);
            return;
        }
        Prefs.setHistory(this, History.toJson(History.add(history, userText, result.text)));

        List<String> parts = Splitter.split(result.text, Prefs.limit(this));
        if (parts.isEmpty()) {
            fail("Gemini zwróciło pustą odpowiedź.");
            return;
        }
        status = "Wysyłam odpowiedź Gemini…";
        // Nowa odpowiedź zastępuje poprzednią, żeby w panelu nie mieszały się części z dwóch odpowiedzi.
        Notifier.cancelRange(this, Notifier.ID_GEMINI_BASE, Notifier.ID_GEMINI_END);
        Notifier.manager(this).cancel(Notifier.ID_ERROR);
        try {
            Notifier.postParts(this, parts, "Gemini", false, Notifier.ID_GEMINI_BASE, Notifier.KIND_GEMINI,
                    Prefs.delayMs(this), Prefs.reverse(this), new Notifier.Control() {
                        @Override
                        public boolean stopped() {
                            return false;
                        }

                        @Override
                        public void progress(int done, int total, String info) {
                            if (info != null) status = info;
                        }
                    });
            status = "Odpowiedź Gemini wysłana: " + parts.size() + " " + Notifier.powiadomien(parts.size()) + ".";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "Przerwano wysyłanie odpowiedzi Gemini.";
        }
    }

    /** Wysyła ponownie powiadomienie, na które odpowiedziano; dzięki temu znika „kręcące się kółko” przy polu odpowiedzi. */
    private void refreshReplied(Job job) {
        if (job.kind == null) return;
        if (Notifier.KIND_ASK.equals(job.kind)) {
            Notifier.postAsk(this, true);
        } else if (job.id != 0) {
            Notifier.post(this, job.id, job.title == null ? "" : job.title, job.text, job.kind, job.when, true);
        }
    }

    private void fail(String message) {
        status = message;
        Notifier.postError(this, message);
    }

    private Notification progressNotification() {
        return new Notification.Builder(this, Notifier.CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(getColor(R.color.accent))
                .setContentTitle("Pytam Gemini")
                .setContentText("Czekam na odpowiedź…")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
