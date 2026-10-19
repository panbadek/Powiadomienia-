package pl.dzielnik;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import java.util.List;

/**
 * Wysyła kolejne części tekstu jako osobne powiadomienia i/lub wiadomości w Telegramie (zależnie od
 * ustawienia „Gdzie wysyłać”), z przerwą między nimi.
 * Działa jako usługa pierwszoplanowa, więc wysyłanie trwa też po wyjściu z aplikacji.
 */
public class SendService extends Service {

    private static final String ACTION_STOP = "pl.dzielnik.STOP";

    private static volatile List<String> pending;
    private static volatile long delayMs = 2000;
    private static volatile boolean reverseOrder;

    // Stan wysyłania, odczytywany przez ekran aplikacji.
    static volatile boolean running;
    static volatile boolean completed;
    static volatile int totalParts;
    static volatile int sentParts;
    /** Opis błędu wysyłania do Telegrama (po polsku) albo null. */
    static volatile String error;

    private volatile boolean stopRequested;
    private Thread worker;

    /**
     * @param reverse true = od ostatniej części do pierwszej (część 1 przychodzi na końcu,
     *                więc w panelu powiadomień, gdzie najnowsze są na górze, jest pierwsza od góry)
     */
    static boolean start(Context c, List<String> parts, long delayMs, boolean reverse) {
        if (running) return false;
        pending = parts;
        SendService.delayMs = delayMs;
        reverseOrder = reverse;
        totalParts = parts.size();
        sentParts = 0;
        completed = false;
        error = null;
        running = true;
        try {
            c.startForegroundService(new Intent(c, SendService.class));
        } catch (RuntimeException e) {
            running = false;
            pending = null;
            return false;
        }
        return true;
    }

    static void requestStop(Context c) {
        c.startService(new Intent(c, SendService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRequested = true;
            if (worker != null) worker.interrupt();
            else finish();
            return START_NOT_STICKY;
        }

        Notifier.ensureChannels(this);
        final List<String> parts = pending;
        int total = parts == null ? 0 : parts.size();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_PROGRESS, progressNotification(0, total, null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifier.ID_PROGRESS, progressNotification(0, total, null));
        }

        if (total == 0 || worker != null) {
            if (worker == null) finish();
            return START_NOT_STICKY;
        }
        final boolean reverse = reverseOrder;
        worker = new Thread(() -> run(parts, reverse), "dzielnik-wysylka");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(List<String> parts, boolean reverse) {
        final NotificationManager nm = Notifier.manager(this);
        int flags = Dest.flags(Prefs.dest(this), Prefs.telegramReady(this), false);
        boolean toTelegram = (flags & Dest.F_TELEGRAM) != 0;
        boolean toNotifications = (flags & Dest.F_NOTIFICATIONS) != 0;

        // Telegram najpierw: to zwykle krótka praca przez sieć, a powiadomienia mogą czekać na użytkownika
        // (przy limicie 50 naraz).
        final List<String> messages = toTelegram ? Telegram.format(Telegram.fit(parts), "Część", true, reverse) : null;
        final int telegramTotal = messages == null ? 0 : messages.size();
        final int grandTotal = telegramTotal + (toNotifications ? parts.size() : 0);
        totalParts = grandTotal;
        try {
            if (toTelegram) {
                String problem = Telegram.sendAll(Telegram.DEFAULT_BASE, Prefs.telegramToken(this),
                        Prefs.telegramChat(this), messages, delayMs, () -> stopRequested,
                        (done, total) -> {
                            sentParts = done;
                            nm.notify(Notifier.ID_PROGRESS, progressNotification(done, grandTotal, "Wysyłam do Telegrama"));
                        }, new int[]{2000, 5000});
                if (problem != null) {
                    error = problem;
                    Notifier.postError(this, "Telegram: coś poszło nie tak", problem);
                    return;
                }
            }
            if (toNotifications && !stopRequested) {
                Notifier.postParts(this, parts, "Część", true, Notifier.ID_PART_BASE, Notifier.KIND_PART,
                        delayMs, reverse, new Notifier.Control() {
                            @Override
                            public boolean stopped() {
                                return stopRequested;
                            }

                            @Override
                            public void progress(int done, int total, String info) {
                                if (info == null) sentParts = telegramTotal + done;
                                nm.notify(Notifier.ID_PROGRESS,
                                        progressNotification(telegramTotal + done, grandTotal, info));
                            }
                        });
            }
        } catch (InterruptedException ignored) {
            // zatrzymano wysyłanie
        } finally {
            finish();
        }
    }

    private void finish() {
        completed = !stopRequested && totalParts > 0 && sentParts >= totalParts;
        running = false;
        pending = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification progressNotification(int done, int total, String info) {
        Intent stop = new Intent(this, SendService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif), "Zatrzymaj", pi).build();
        return new Notification.Builder(this, Notifier.CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(getColor(R.color.accent))
                .setContentTitle("Wysłano " + done + " z " + total)
                .setContentText(info == null ? "Dzielnik wysyła tekst" : info)
                .setProgress(total, done, false)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
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
