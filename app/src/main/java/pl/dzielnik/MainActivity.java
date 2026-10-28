package pl.dzielnik;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final int DEFAULT_TEST = 400;
    private static final int REQUEST_PHOTO = 41;

    private EditText text, limit, delay, testLen, seen, gemKey, gemModel, gemQuestion, photoPrompt, tgToken;
    private TextView preview, result, status, measureToggle, gemStatus, photoStatus, tgStatus;
    private Button send, use;
    private Switch reverse, replySwitch, tgListen;
    private RadioGroup destGroup;
    private ProgressBar progress;
    private View measureBody;

    // Telegram: komunikat o ostatniej czynności (łączenie, test) i to, czy akurat coś się dzieje w tle.
    private String tgNote = "";
    private long tgNoteAt;
    private boolean tgBusy;
    private boolean updatingSwitch;

    // Aparat jest otwarty i czekam na zdjęcie (drugie dotknięcie przycisku w tym czasie nic nie robi).
    private boolean cameraOpen;

    private int measured = -1;
    private boolean wasRunning;
    private Boolean shownRunning;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateSendState();
            updateGeminiState();
            updateTelegramState();
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
        gemKey = findViewById(R.id.gem_key);
        gemModel = findViewById(R.id.gem_model);
        gemQuestion = findViewById(R.id.gem_question);
        gemStatus = findViewById(R.id.gem_status);
        photoPrompt = findViewById(R.id.photo_prompt);
        photoStatus = findViewById(R.id.photo_status);
        replySwitch = findViewById(R.id.sw_reply);
        tgToken = findViewById(R.id.tg_token);
        tgStatus = findViewById(R.id.tg_status);
        tgListen = findViewById(R.id.sw_tg_listen);
        destGroup = findViewById(R.id.rg_dest);

        SharedPreferences sp = Prefs.get(this);
        limit.setText(String.valueOf(sp.getInt(Prefs.LIMIT, Prefs.DEFAULT_LIMIT)));
        delay.setText(sp.getString(Prefs.DELAY, "2"));
        testLen.setText(String.valueOf(sp.getInt(Prefs.TEST_LEN, DEFAULT_TEST)));
        reverse.setChecked(sp.getBoolean(Prefs.REVERSE, false));
        gemKey.setText(sp.getString(Prefs.GEMINI_KEY, ""));
        gemModel.setText(sp.getString(Prefs.GEMINI_MODEL, Gemini.DEFAULT_MODEL));
        photoPrompt.setText(Prefs.photoPrompt(this));
        replySwitch.setChecked(sp.getBoolean(Prefs.GEMINI_REPLY, true));
        tgToken.setText(sp.getString(Prefs.TG_TOKEN, ""));
        destGroup.check(destRadioId(Prefs.dest(this)));
        setListenSwitch(sp.getBoolean(Prefs.TG_LISTEN, false));

        letScrollInside(text);
        letScrollInside(gemQuestion);
        letScrollInside(photoPrompt);

        Watcher watcher = new Watcher(this::updatePreview);
        text.addTextChangedListener(watcher);
        limit.addTextChangedListener(watcher);

        findViewById(R.id.btn_paste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btn_clear).setOnClickListener(v -> text.setText(""));
        findViewById(R.id.measure_header).setOnClickListener(v -> toggleMeasure());
        findViewById(R.id.btn_test).setOnClickListener(v -> onTest());
        findViewById(R.id.btn_decode).setOnClickListener(v -> onDecode());
        findViewById(R.id.btn_ask).setOnClickListener(v -> onAsk());
        findViewById(R.id.btn_ask_notif).setOnClickListener(v -> onAskNotification());
        findViewById(R.id.btn_photo).setOnClickListener(v -> onPhoto());
        findViewById(R.id.btn_new_chat).setOnClickListener(v -> onNewChat());
        findViewById(R.id.btn_tg_connect).setOnClickListener(v -> onTgConnect());
        findViewById(R.id.btn_tg_test).setOnClickListener(v -> onTgTest());
        findViewById(R.id.btn_battery).setOnClickListener(v -> onBatterySettings());
        tgListen.setOnCheckedChangeListener((button, on) -> onTgListenChanged(on));
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
        restartListenerIfNeeded();
        handler.post(refresh);
    }

    /**
     * Nasłuch Telegrama ma działać, ale system mógł zabić usługę (restart telefonu, wyczyszczenie aplikacji
     * z listy ostatnich, limit czasu usług w tle): uruchamiam ją znowu przy każdym wejściu do aplikacji.
     */
    private void restartListenerIfNeeded() {
        if (Prefs.telegramListen(this) && Prefs.telegramReady(this) && !TelegramService.running) {
            TelegramService.start(this);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        saveSettings();
        super.onPause();
    }

    /** Pole tekstu przewija się samo, mimo że leży w ScrollView. */
    private static void letScrollInside(EditText field) {
        field.setOnTouchListener((v, ev) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if ((ev.getAction() & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
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
            toast("Wysyłam " + parts.size() + " " + (parts.size() == 1 ? "część" : "części") + "…");
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
            String error = SendService.error;
            if (error != null) {
                status.setText(error + " Wysłano " + sent + " z " + total + ".");
            } else if (SendService.completed) {
                status.setText("Gotowe! Wysłano wszystko (" + total + "). " + whereToLook());
            } else {
                status.setText("Zatrzymano. Wysłano " + sent + " z " + total + ".");
            }
        }
        wasRunning = running;
    }

    // ---------- Gemini ----------

    private void onAsk() {
        String question = gemQuestion.getText().toString().trim();
        if (question.isEmpty()) {
            toast("Wpisz pytanie do Gemini.");
            return;
        }
        if (gemKey.getText().toString().trim().isEmpty()) {
            toast("Najpierw wklej klucz API Gemini.");
            return;
        }
        if (!notificationsAllowed()) return;
        saveSettings();
        if (GeminiService.start(this, question)) {
            gemQuestion.setText("");
            toast(Prefs.dest(this) == Dest.TELEGRAM && Prefs.telegramReady(this)
                    ? "Pytam Gemini. Odpowiedź przyjdzie do Telegrama."
                    : "Pytam Gemini. Odpowiedź przyjdzie jako powiadomienia.");
        } else {
            toast("Nie udało się wysłać pytania. Spróbuj jeszcze raz przy otwartej aplikacji.");
        }
    }

    private void onAskNotification() {
        if (gemKey.getText().toString().trim().isEmpty()) {
            toast("Najpierw wklej klucz API Gemini.");
            return;
        }
        if (!notificationsAllowed()) return;
        saveSettings();
        Notifier.postAsk(this, false);
        toast("Wstawiłem powiadomienie „Zapytaj Gemini”. Naciśnij w nim „Odpowiedz”.");
    }

    private void onNewChat() {
        Answerer.newChat(this);
        toast("Zaczynam nową rozmowę. Gemini nie pamięta poprzednich pytań.");
    }

    /** Pokazuje, co dzieje się z ostatnim pytaniem do Gemini: pod polem pytania albo pod przyciskiem zdjęcia. */
    private void updateGeminiState() {
        String st = GeminiService.status;
        boolean photo = GeminiService.statusIsPhoto;
        showStatus(gemStatus, photo ? "" : st);
        showStatus(photoStatus, photo ? st : "");
    }

    private static void showStatus(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- Zdjęcie ----------

    /**
     * Otwiera aparat telefonu. Zdjęcie ląduje w pamięci podręcznej aplikacji (przez PhotoProvider), więc nie trzeba
     * pozwolenia na aparat ani na pliki, i nie trafia do galerii. Dalej zajmuje się nim PhotoFlow.
     */
    private void onPhoto() {
        if (cameraOpen) return;
        if (gemKey.getText().toString().trim().isEmpty()) {
            toast("Najpierw wklej klucz API Gemini.");
            return;
        }
        if (!notificationsAllowed()) return;
        saveSettings();
        Intent camera = PhotoFlow.beginCapture(this, photoPrompt.getText().toString());
        try {
            startActivityForResult(camera, REQUEST_PHOTO);
            cameraOpen = true;
        } catch (RuntimeException e) {
            PhotoFlow.abortCapture(this);
            toast("Nie udało się otworzyć aparatu. Sprawdź, czy w telefonie jest aplikacja aparatu.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO) return;
        cameraOpen = false;
        switch (PhotoFlow.finishCapture(this, resultCode == RESULT_OK)) {
            case PhotoFlow.MISSING:
                toast("Aparat nie zapisał zdjęcia. Spróbuj jeszcze raz.");
                break;
            case PhotoFlow.SENT:
                toast("Wysyłam zdjęcie do Gemini. Odpowiedź przyjdzie " + answerWhere() + ".");
                break;
            case PhotoFlow.NOT_STARTED:
                toast("Nie udało się wysłać zdjęcia. Spróbuj jeszcze raz przy otwartej aplikacji.");
                break;
            default:
                // nic nie czekało albo zrezygnowano w aparacie
                break;
        }
    }

    /** Dokąd trafi odpowiedź, zgodnie z ustawieniem „Gdzie wysyłać”. */
    private String answerWhere() {
        int flags = Dest.flags(Prefs.dest(this), Prefs.telegramReady(this), false);
        boolean tg = (flags & Dest.F_TELEGRAM) != 0;
        boolean notif = (flags & Dest.F_NOTIFICATIONS) != 0;
        if (tg && notif) return "jako powiadomienia i w Telegramie";
        return tg ? "do Telegrama" : "jako powiadomienia";
    }

    // ---------- Telegram ----------

    /** Podpowiedź na koniec wysyłania: gdzie szukać części. */
    private String whereToLook() {
        int flags = Dest.flags(Prefs.dest(this), Prefs.telegramReady(this), false);
        boolean tg = (flags & Dest.F_TELEGRAM) != 0;
        boolean notif = (flags & Dest.F_NOTIFICATIONS) != 0;
        if (tg && notif) return "Zajrzyj do panelu powiadomień i do Telegrama.";
        return tg ? "Zajrzyj do Telegrama." : "Zajrzyj do panelu powiadomień.";
    }

    private void setNote(String note) {
        tgNote = note;
        tgNoteAt = System.currentTimeMillis();
    }

    private void setListenSwitch(boolean on) {
        updatingSwitch = true;
        tgListen.setChecked(on);
        updatingSwitch = false;
    }

    private static int destRadioId(int dest) {
        if (dest == Dest.TELEGRAM) return R.id.rb_dest_tg;
        if (dest == Dest.BOTH) return R.id.rb_dest_both;
        return R.id.rb_dest_notif;
    }

    private int selectedDest() {
        int id = destGroup.getCheckedRadioButtonId();
        if (id == R.id.rb_dest_tg) return Dest.TELEGRAM;
        if (id == R.id.rb_dest_both) return Dest.BOTH;
        return Dest.NOTIFICATIONS;
    }

    /** Sprawdza token, szuka czatu (po wiadomości „/start” do bota) i wysyła powitanie. */
    private void onTgConnect() {
        if (tgBusy) return;
        if (TelegramService.running) {
            toast("Najpierw wyłącz nasłuch, potem połącz od nowa.");
            return;
        }
        final String token = Telegram.cleanToken(tgToken.getText().toString());
        if (!Telegram.validToken(token)) {
            toast("To nie wygląda jak token bota. Ma postać 123456789:ABC… i dostaje się go od @BotFather.");
            return;
        }
        saveSettings();
        tgBusy = true;
        setNote("Łączę z Telegramem…");
        new Thread(() -> {
            final Telegram.Link link = Telegram.link(Telegram.DEFAULT_BASE, token, 15000, 30000);
            handler.post(() -> onTgLinked(token, link));
        }, "dzielnik-polacz").start();
    }

    private void onTgLinked(String token, Telegram.Link link) {
        tgBusy = false;
        if (!Telegram.cleanToken(tgToken.getText().toString()).equals(token)) {
            setNote("");
            return;
        }
        if (link.error != null) {
            setNote(link.error);
            return;
        }
        SharedPreferences.Editor ed = Prefs.get(this).edit();
        ed.putString(Prefs.TG_BOT, link.bot);
        if (!link.linked()) {
            ed.apply();
            setNote("Token działa (" + link.bot + "), ale nikt jeszcze nie napisał do bota. Otwórz go w Telegramie, "
                    + "napisz /start i naciśnij „Połącz” jeszcze raz.");
            return;
        }
        ed.putLong(Prefs.TG_CHAT, link.chatId);
        if (link.nextOffset > 0) ed.putLong(Prefs.TG_OFFSET, link.nextOffset);
        ed.apply();
        setNote("Połączono z " + link.bot + (link.chatName.isEmpty() ? "" : ", czat: " + link.chatName)
                + ". Powitanie poszło do Telegrama.");
    }

    private void onTgTest() {
        saveSettings();
        if (!Prefs.telegramReady(this)) {
            toast("Najpierw połącz z botem: wklej token, napisz do bota /start i naciśnij „Połącz”.");
            return;
        }
        final String token = Prefs.telegramToken(this);
        final long chat = Prefs.telegramChat(this);
        setNote("Wysyłam wiadomość testową…");
        new Thread(() -> {
            final Telegram.Reply r = Telegram.sendMessage(Telegram.DEFAULT_BASE, token, chat,
                    "Test z aplikacji Powiadomienia. Odpowiedz na tę wiadomość z zegarka: jeśli nasłuch jest włączony, "
                            + "aplikacja zapyta Gemini i odeśle odpowiedź.", 15000, 30000);
            handler.post(() -> setNote(r.ok()
                    ? "Wiadomość testowa wysłana. Sprawdź telefon i zegarek."
                    : r.error));
        }, "dzielnik-test-telegram").start();
    }

    private void onTgListenChanged(boolean on) {
        if (updatingSwitch) return;
        if (!on) {
            TelegramService.stop(this);
            toast("Nasłuch wyłączony.");
            return;
        }
        saveSettings();
        if (!Prefs.telegramReady(this)) {
            toast("Najpierw połącz z botem: wklej token, napisz do bota /start i naciśnij „Połącz”.");
            setListenSwitch(false);
        } else if (Prefs.geminiKey(this).isEmpty()) {
            toast("Najpierw wklej klucz API Gemini.");
            setListenSwitch(false);
        } else if (!notificationsAllowed()) {
            setListenSwitch(false);
        } else if (TelegramService.start(this)) {
            toast("Nasłuchuję pytań z Telegrama.");
        } else {
            toast("Nie udało się uruchomić nasłuchu. Spróbuj jeszcze raz przy otwartej aplikacji.");
            setListenSwitch(false);
        }
    }

    /** Prośba o zwolnienie aplikacji z oszczędzania baterii; gdy się nie da, otwiera listę w ustawieniach. */
    private void onBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException e2) {
                toast("Otwórz ręcznie: Ustawienia → Aplikacje → Powiadomienia → Bateria → Bez ograniczeń.");
            }
        }
    }

    /** Pokazuje, czy bot jest połączony i czy nasłuch działa. */
    private void updateTelegramState() {
        if (!tgBusy && !tgNote.isEmpty() && System.currentTimeMillis() - tgNoteAt > 25000) tgNote = "";
        String line;
        if (!tgNote.isEmpty()) {
            line = tgNote;
        } else if (!Prefs.telegramReady(this)) {
            line = "Nie połączono. Wklej token od @BotFather, napisz do bota /start i naciśnij „Połącz”.";
        } else {
            String bot = Prefs.get(this).getString(Prefs.TG_BOT, "");
            line = "Połączono" + (bot.isEmpty() ? "" : " z " + bot) + ". ";
            String st = TelegramService.status;
            if (TelegramService.running) {
                line += st.isEmpty() ? "Nasłuchuję." : st;
            } else if (Prefs.telegramListen(this)) {
                line += "Nasłuch jest włączony, ale usługa nie działa. Wyłącz go i włącz ponownie."
                        + (st.isEmpty() ? "" : " " + st);
            } else {
                line += "Nasłuch wyłączony." + (st.isEmpty() ? "" : " " + st);
            }
        }
        if (!line.contentEquals(tgStatus.getText())) tgStatus.setText(line);

        boolean want = Prefs.telegramListen(this);
        if (tgListen.isChecked() != want) setListenSwitch(want);
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
        toast("Zezwól aplikacji na powiadomienia (Ustawienia → Aplikacje → Powiadomienia) i spróbuj jeszcze raz.");
        return false;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void saveSettings() {
        SharedPreferences.Editor ed = Prefs.get(this).edit();
        int lim = parseInt(limit);
        if (lim >= Splitter.MIN_LIMIT && lim <= Measure.MAX) ed.putInt(Prefs.LIMIT, lim);
        int test = parseInt(testLen);
        if (test >= 9 && test <= Measure.MAX) ed.putInt(Prefs.TEST_LEN, test);
        ed.putString(Prefs.DELAY, String.valueOf(readDelayMs() / 1000.0).replaceAll("\\.0$", ""));
        ed.putBoolean(Prefs.REVERSE, reverse.isChecked());

        ed.putString(Prefs.GEMINI_KEY, gemKey.getText().toString().trim());
        String typedModel = gemModel.getText().toString().trim();
        String model = typedModel.isEmpty() ? Gemini.DEFAULT_MODEL : Gemini.cleanModel(typedModel);
        ed.putString(Prefs.GEMINI_MODEL, model);
        if (!model.equals(typedModel)) gemModel.setText(model);
        ed.putBoolean(Prefs.GEMINI_REPLY, replySwitch.isChecked());
        ed.putString(Prefs.PHOTO_PROMPT, photoPrompt.getText().toString());

        // Nowy token to nowy bot: zapisany czat i numer wiadomości do niego nie pasują, nasłuch trzeba połączyć od nowa.
        String token = Telegram.cleanToken(tgToken.getText().toString());
        if (!token.equals(Prefs.telegramToken(this))) {
            ed.putString(Prefs.TG_TOKEN, token);
            ed.putLong(Prefs.TG_CHAT, 0);
            ed.putString(Prefs.TG_BOT, "");
            ed.putLong(Prefs.TG_OFFSET, 0);
            ed.putBoolean(Prefs.TG_LISTEN, false);
            if (TelegramService.running) TelegramService.stop(this);
            if (!tgNote.isEmpty()) setNote("");
        }
        if (!token.equals(tgToken.getText().toString())) tgToken.setText(token);
        ed.putInt(Prefs.DEST, selectedDest());
        ed.apply();
    }

    /** Przerwa między powiadomieniami w ms; dopuszczam przecinek i kropkę, zakres 0,5–60 s. */
    private long readDelayMs() {
        return Prefs.parseDelayMs(delay.getText().toString());
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
