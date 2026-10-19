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
 * Wysyła kolejne części tekstu jako osobne powiadomienia, z przerwą między nimi.
 * Działa jako usługa pierwszoplanowa, więc wysyłanie trwa też po wyjściu z aplikacji.
 */
public class SendService extends Service {

    private static final String ACTION_STOP = "pl.dzielnik.STOP";

    /** Android pozwala jednej aplikacji mieć naraz 50 powiadomień. Zostawiam zapas na postęp i test. */
    private static final int MAX_ACTIVE = 45;

    private static volatile List<String> pending;
    private static volatile long delayMs = 2000;
    private static volatile boolean reverseOrder;

    // Stan wysyłania, odczytywany przez ekran aplikacji.
    static volatile boolean running;
    static volatile boolean completed;
    static volatile int totalParts;
    static volatile int sentParts;

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
            startForeground(Notifier.ID_PROGRESS, progress(0, total, null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifier.ID_PROGRESS, progress(0, total, null));
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
        NotificationManager nm = Notifier.manager(this);
        int total = parts.size();
        try {
            for (int k = 0; k < total && !stopRequested; k++) {
                int i = reverse ? total - 1 - k : k;

                // Przy limicie 50 powiadomień czekam, aż użytkownik część z nich usunie.
                while (!stopRequested && nm.getActiveNotifications().length >= MAX_ACTIVE) {
                    nm.notify(Notifier.ID_PROGRESS,
                            progress(k, total, "Czekam, aż usuniesz część powiadomień"));
                    Thread.sleep(1500);
                }
                if (stopRequested) break;

                Notifier.post(this, Notifier.ID_PART_BASE + i,
                        "Część " + (i + 1) + "/" + total, parts.get(i));
                sentParts = k + 1;
                nm.notify(Notifier.ID_PROGRESS, progress(k + 1, total, null));
                if (k + 1 < total) Thread.sleep(delayMs);
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

    private Notification progress(int done, int total, String info) {
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
