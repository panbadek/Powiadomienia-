package pl.dzielnik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** Kanały powiadomień i wysyłanie pojedynczych powiadomień. */
final class Notifier {

    static final String CHANNEL_PARTS = "czesci";
    static final String CHANNEL_PROGRESS = "postep";

    static final int ID_PROGRESS = 1;
    static final int ID_TEST = 2;
    static final int ID_PART_BASE = 1000;

    private Notifier() {}

    static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = manager(c);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PARTS, "Części tekstu", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PROGRESS, "Postęp wysyłania", NotificationManager.IMPORTANCE_LOW));
    }

    /** Powiadomienie z jedną częścią tekstu. Treść jest i w widoku zwiniętym, i w rozwiniętym. */
    static void post(Context c, int id, String title, String text) {
        ensureChannels(c);
        Notification n = new Notification.Builder(c, CHANNEL_PARTS)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
        manager(c).notify(id, n);
    }

    /** Powiadomienie testowe do mierzenia długości. */
    static void postTest(Context c, int length) {
        post(c, ID_TEST, "Test długości (" + length + " zn.)", Measure.generate(length));
    }
}
