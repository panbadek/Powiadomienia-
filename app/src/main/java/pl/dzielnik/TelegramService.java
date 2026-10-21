package pl.dzielnik;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Nasłuchuje wiadomości do bota w Telegramie. Odpowiedź napisana na zegarku (albo w telefonie) do
 * wiadomości od bota trafia tu, idzie do Gemini, a odpowiedź Gemini wraca do Telegrama.
 *
 * Działa jako usługa pierwszoplanowa (z ikoną w pasku powiadomień), dzięki temu system nie zabija jej
 * po wyjściu z aplikacji. Przy ponownym uruchomieniu telefonu trzeba raz otworzyć aplikację.
 */
public class TelegramService extends Service {

    static final String ACTION_STOP = "pl.dzielnik.TG_STOP";

    private static final int POLL_SECONDS = 30;
    private static final int MAX_QUESTION = 8000;

    /** Czy nasłuch działa; czyta to ekran aplikacji. */
    static volatile boolean running;

    /** Ostatni komunikat o nasłuchu (kłopot z siecią, błąd tokenu), pokazywany na ekranie aplikacji. */
    static volatile String status = "";

    private volatile boolean stopRequested;
    private Thread worker;

    /** Włącza nasłuch i zapamiętuje, że ma działać (aplikacja uruchomi go ponownie po otwarciu). */
    static boolean start(Context c) {
        if (running) return true;
        Prefs.setTelegramListen(c, true);
        running = true;
        status = "";
        try {
            c.startForegroundService(new Intent(c, TelegramService.class));
        } catch (RuntimeException e) {
            running = false;
            status = "Nie udało się uruchomić nasłuchu. Spróbuj jeszcze raz przy otwartej aplikacji.";
            return false;
        }
        return true;
    }

    static void stop(Context c) {
        Prefs.setTelegramListen(c, false);
        c.startService(new Intent(c, TelegramService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setTelegramListen(this, false);
            stopRequested = true;
            if (worker != null) worker.interrupt();
            running = false;
            status = "";
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notifier.ensureChannels(this);
        try {
            Notification n = listeningNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifier.ID_LISTEN, n);
            }
        } catch (RuntimeException e) {
            // System nie pozwolił na usługę pierwszoplanową (np. start z tła po zabiciu procesu).
            running = false;
            status = "System nie pozwolił uruchomić nasłuchu w tle. Otwórz aplikację, żeby uruchomić go ponownie.";
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Prefs.telegramReady(this) || !Prefs.telegramListen(this)) {
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (worker == null || !worker.isAlive()) {
            stopRequested = false;
            worker = new Thread(this::listen, "dzielnik-telegram");
            worker.start();
        }
        return START_STICKY;
    }

    private void listen() {
        final Context c = this;
        TelegramBridge.Host host = new TelegramBridge.Host() {
            @Override
            public boolean stopped() {
                return stopRequested;
            }

            @Override
            public long nextOffset() {
                return Prefs.telegramOffset(c);
            }

            @Override
            public void saveOffset(long next) {
                Prefs.setTelegramOffset(c, next);
            }

            @Override
            public void message(Telegram.Update u) {
                handleMessage(u);
            }

            @Override
            public void problem(String text) {
                status = text == null ? "Nasłuchuję pytań z Telegrama." : text + " Ponawiam.";
            }

            @Override
            public void fatal(String text) {
                status = text;
                Prefs.setTelegramListen(c, false);
                Notifier.postError(c, "Telegram: coś poszło nie tak", text);
            }
        };
        status = "Nasłuchuję pytań z Telegrama.";
        try {
            TelegramBridge.run(Telegram.DEFAULT_BASE, Prefs.telegramToken(this), Prefs.telegramChat(this), host,
                    POLL_SECONDS, new int[]{3000, 6000, 12000, 30000}, 1000);
        } catch (InterruptedException ignored) {
            // zatrzymano nasłuch
        } catch (RuntimeException e) {
            if (!stopRequested) status = "Nasłuch przerwany błędem: " + e;
        } finally {
            // Sprzątam tylko wtedy, gdy pętla skończyła się sama (zły token albo wyjątek). Po prośbie o zatrzymanie
            // zrobiły to już onStartCommand i onDestroy. Stary wątek kończy się dopiero po powrocie długiego
            // zapytania do Telegrama, więc nie może wyłączyć nasłuchu, który ktoś w międzyczasie włączył na nowo.
            if (!stopRequested) {
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    /** Wiadomość od właściciela: komenda, pytanie albo coś bez tekstu. */
    private void handleMessage(Telegram.Update u) {
        if (u.text == null) {
            reply("Rozumiem tylko wiadomości tekstowe.");
            return;
        }
        String text = u.text.trim();
        if (text.isEmpty()) return;

        String command = Telegram.command(text);
        if (command != null) {
            handleCommand(command);
            return;
        }
        if (text.length() > MAX_QUESTION) text = text.substring(0, MAX_QUESTION);
        // Gdy odpowiadam na konkretną wiadomość od bota, jej treść idzie do Gemini razem z odpowiedzią.
        String quoted = u.quotedFromBot && u.quoted != null ? u.quoted : "";

        // Na czas pytania procesor ma nie zasnąć, bo odpowiedź Gemini potrafi trwać kilkanaście sekund.
        PowerManager.WakeLock lock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dzielnik:telegram");
            lock.setReferenceCounted(false);
            lock.acquire(10 * 60 * 1000L);
        } catch (RuntimeException ignored) {
            lock = null;
        }
        try {
            Answerer.answer(this, text, quoted, true);
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    private void handleCommand(String command) {
        switch (command) {
            case "start":
            case "pomoc":
            case "help":
                reply("Napisz pytanie, a zapytam Gemini i odeślę odpowiedź. Komenda /nowa zaczyna nową rozmowę.");
                break;
            case "nowa":
                Answerer.newChat(this);
                reply("Zaczynam nową rozmowę. Gemini nie pamięta poprzednich pytań.");
                break;
            default:
                reply("Nie znam komendy /" + command + ". Napisz zwykłe pytanie albo użyj /nowa.");
        }
    }

    private void reply(String text) {
        Telegram.sendMessage(Telegram.DEFAULT_BASE, Prefs.telegramToken(this), Prefs.telegramChat(this), text,
                15000, 30000);
    }

    private Notification listeningNotification() {
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, TelegramService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif), "Zatrzymaj", stop).build();
        return new Notification.Builder(this, Notifier.CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(getColor(R.color.accent))
                .setContentTitle("Nasłuch Telegrama włączony")
                .setContentText("Odpowiedzi z zegarka trafiają do Gemini.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    /**
     * Android 15 i nowszy mogą zakończyć usługę typu dataSync po 6 godzinach pracy w ciągu doby (dotyczy aplikacji
     * zbudowanych na API 35 i wyżej, a ta aplikacja jest na API 34, więc zwykle do tego nie dochodzi). Gdyby jednak do tego
     * doszło, usługa ma kilka sekund na zatrzymanie się, inaczej system wywraca aplikację. Ustawienie „nasłuchuj”
     * zostaje, więc po otwarciu aplikacji nasłuch włączy się sam.
     * Metoda nie ma @Override, bo istnieje dopiero w API 35, a aplikacja jest budowana na API 34.
     */
    public void onTimeout(int startId, int fgsType) {
        stopRequested = true;
        if (worker != null) worker.interrupt();
        running = false;
        status = "System zatrzymał nasłuch po wielu godzinach pracy w tle. Otwórz aplikację, żeby włączyć go ponownie.";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        if (worker != null) worker.interrupt();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
