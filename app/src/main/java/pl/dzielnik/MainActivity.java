package pl.dzielnik;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "dzielnik";
    private static final int DEFAULT_LIMIT = 200;
    private static final int DEFAULT_TEST = 400;

    private EditText text, limit, delay, testLen, seen;
    private TextView preview, result, status, measureToggle;
    private Button send, use;
    private Switch reverse;
    private ProgressBar progress;
    private View measureBody;

    private int measured = -1;
    private boolean wasRunning;
    private Boolean shownRunning;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateSendState();
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        text = findViewById(R.id.text);
        limit = findViewById(R.id.limit);
        delay = findViewById(R.id.delay);
        testLen = findViewById(R.id.test_len);
        seen = findViewById(R.id.seen);
        preview = findViewById(R.id.preview);
        result = findViewById(R.id.result);
        status = findViewById(R.id.status);
        measureToggle = findViewById(R.id.measure_toggle);
        measureBody = findViewById(R.id.measure_body);
        send = findViewById(R.id.btn_send);
        use = findViewById(R.id.btn_use);
        reverse = findViewById(R.id.sw_reverse);
        progress = findViewById(R.id.progress);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        limit.setText(String.valueOf(sp.getInt("limit", DEFAULT_LIMIT)));
        delay.setText(sp.getString("delay", "2"));
        testLen.setText(String.valueOf(sp.getInt("testLen", DEFAULT_TEST)));
        reverse.setChecked(sp.getBoolean("reverse", false));

        // pole tekstu przewija się samo, mimo że leży w ScrollView
        text.setOnTouchListener((v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });

        Watcher watcher = new Watcher(this::updatePreview);
        text.addTextChangedListener(watcher);
        limit.addTextChangedListener(watcher);

        findViewById(R.id.btn_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_clear).setOnClickListener(v -> text.setText(""));
        findViewById(R.id.measure_header).setOnClickListener(v -> toggleMeasure());
        findViewById(R.id.btn_test).setOnClickListener(v -> onTest());
        findViewById(R.id.btn_decode).setOnClickListener(v -> onDecode());
        send.setOnClickListener(v -> onSend());
        use.setOnClickListener(v -> {
            if (measured >= Splitter.MIN_LIMIT) {
                limit.setText(String.valueOf(measured));
                saveSettings();
                toast("Długość części ustawiona na " + measured + " znaków.");
            }
        });

        if (state == null) handleIntent(getIntent());
        askNotificationPermission();
        updatePreview();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        saveSettings();
        super.onPause();
    }

    // ---------- wysyłanie ----------

    private void onSend() {
        if (SendService.running) {
            SendService.requestStop(this);
            toast("Zatrzymuję wysyłanie…");
            return;
        }
        int lim = parseInt(limit);
        if (lim < Splitter.MIN_LIMIT || lim > Measure.MAX) {
            toast("Długość części musi mieścić się między " + Splitter.MIN_LIMIT + " a " + Measure.MAX + " znaków.");
            return;
        }
        List<String> parts = Splitter.split(text.getText().toString(), lim);
        if (parts.isEmpty()) {
            toast("Najpierw wklej tekst.");
            return;
        }
        if (!notificationsAllowed()) return;
        saveSettings();
        if (SendService.start(this, parts, readDelayMs(), reverse.isChecked())) {
            status.setText("Zaczynam wysyłać…");
            toast("Wysyłam " + parts.size() + " " + powiadomien(parts.size()) + "…");
        } else {
            toast("Nie udało się uruchomić wysyłania. Spróbuj jeszcze raz przy otwartej aplikacji.");
        }
    }

    /** Odświeża przycisk, pasek postępu i podpis podczas wysyłania. */
    private void updateSendState() {
        boolean running = SendService.running;
        if (shownRunning == null || shownRunning != running) {
            shownRunning = running;
            if (running) {
                send.setText("Zatrzymaj wysyłanie");
                send.setBackgroundResource(R.drawable.bg_btn_stop);
                progress.setVisibility(View.VISIBLE);
            } else {
                send.setText("Podziel i wyślij");
                send.setBackgroundResource(R.drawable.bg_btn_primary);
                progress.setVisibility(View.GONE);
            }
        }
        int total = SendService.totalParts;
        int sent = SendService.sentParts;
        if (running) {
            progress.setMax(Math.max(1, total));
            progress.setProgress(sent);
            status.setText("Wysłano " + sent + " z " + total);
        } else if (wasRunning) {
            status.setText(SendService.completed
                    ? "Gotowe! Wysłano wszystko (" + total + "). Zajrzyj do panelu powiadomień."
                    : "Zatrzymano. Wysłano " + sent + " z " + total + ".");
        }
        wasRunning = running;
    }

    // ---------- pomiar ----------

    private void toggleMeasure() {
        boolean open = measureBody.getVisibility() != View.VISIBLE;
        measureBody.setVisibility(open ? View.VISIBLE : View.GONE);
        measureToggle.setText(open ? "Ukryj" : "Pokaż");
    }

    private void onTest() {
        int len = parseInt(testLen);
        if (len < 9 || len > Measure.MAX) {
            toast("Długość testu musi mieścić się między 9 a " + Measure.MAX + " znaków.");
            return;
        }
        if (!notificationsAllowed()) return;
        saveSettings();
        Notifier.postTest(this, len);
        toast("Wysłano test. Rozwiń powiadomienie i przepisz końcówkę.");
    }

    private void onDecode() {
        int len = Measure.decode(seen.getText().toString());
        if (len < 0) {
            measured = -1;
            use.setEnabled(false);
            use.setText("Ustaw jako długość części");
            result.setText("Nie rozpoznaję tego zapisu. Przepisz końcówkę razem ze średnikiem przed ostatnią liczbą, np. ;120;45");
            return;
        }
        measured = len;
        result.setText("W powiadomieniu było widać " + len + " znaków.");
        boolean usable = len >= Splitter.MIN_LIMIT;
        use.setEnabled(usable);
        use.setText(usable ? "Ustaw długość części: " + len : "Ustaw jako długość części");
    }

    // ---------- pomocnicze ----------

    private void handleIntent(Intent in) {
        if (in != null && Intent.ACTION_SEND.equals(in.getAction())) {
            CharSequence shared = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null) text.setText(shared);
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("Schowek jest pusty.");
            return;
        }
        CharSequence s = clip.getItemAt(0).coerceToText(this);
        if (s == null || s.length() == 0) {
            toast("W schowku nie ma tekstu.");
            return;
        }
        text.setText(s);
    }

    private void updatePreview() {
        int lim = parseInt(limit);
        String t = text.getText().toString();
        if (lim < Splitter.MIN_LIMIT || lim > Measure.MAX) {
            preview.setText("Podaj długość części od " + Splitter.MIN_LIMIT + " do " + Measure.MAX + " znaków.");
        } else if (t.trim().isEmpty()) {
            preview.setText("Wklej tekst, a tu zobaczysz, na ile części zostanie podzielony.");
        } else {
            List<String> parts = Splitter.split(t, lim);
            int longest = 0;
            for (String p : parts) longest = Math.max(longest, p.length());
            preview.setText("Tekst (" + t.length() + " zn.) zostanie podzielony na " + parts.size() + " "
                    + (parts.size() == 1 ? "część" : "części") + ". Najdłuższa ma " + longest + " zn.");
        }
    }

    private boolean notificationsAllowed() {
        if (Notifier.manager(this).areNotificationsEnabled()) return true;
        askNotificationPermission();
        toast("Zezwól aplikacji na powiadomienia (Ustawienia → Aplikacje → Dzielnik) i spróbuj jeszcze raz.");
        return false;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void saveSettings() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        int lim = parseInt(limit);
        if (lim >= Splitter.MIN_LIMIT && lim <= Measure.MAX) ed.putInt("limit", lim);
        int test = parseInt(testLen);
        if (test >= 9 && test <= Measure.MAX) ed.putInt("testLen", test);
        ed.putString("delay", String.valueOf(readDelayMs() / 1000.0).replaceAll("\\.0$", ""));
        ed.putBoolean("reverse", reverse.isChecked());
        ed.apply();
    }

    /** Przerwa między powiadomieniami w ms; dopuszczam przecinek i kropkę, zakres 0,5–60 s. */
    private long readDelayMs() {
        double sec = 2;
        try {
            sec = Double.parseDouble(delay.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ignored) {
            // zostaje 2 s
        }
        sec = Math.max(0.5, Math.min(60, sec));
        return Math.round(sec * 1000);
    }

    private static String powiadomien(int n) {
        if (n == 1) return "powiadomienie";
        int last = n % 10;
        int last2 = n % 100;
        if (last >= 2 && last <= 4 && !(last2 >= 12 && last2 <= 14)) return "powiadomienia";
        return "powiadomień";
    }

    private static int parseInt(EditText e) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static final class Watcher implements TextWatcher {
        private final Runnable action;

        Watcher(Runnable action) {
            this.action = action;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void afterTextChanged(Editable s) {
            action.run();
        }
    }
}
