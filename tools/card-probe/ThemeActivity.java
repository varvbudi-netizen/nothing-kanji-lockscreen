package ru.local.nihongo.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.content.pm.PackageManager;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Настройки оформления и состава словаря карточки. */
public final class ThemeActivity extends Activity {
    private static final int CHANGE_THEME = 1;
    private static final int CHANGE_LEVELS = 2;
    private static final int NEXT_KANJI = 3;
    private static final int INK = Color.rgb(23, 35, 48);
    private static final int PAPER = Color.rgb(255, 247, 233);
    private TextView status, current, autoStatus;
    private Button plain, fuji, next, about;
    private CheckBox n5, n4, auto;
    private KanjiCatalog catalog;
    private boolean refreshing;
    private boolean autoStartFailed;

    // Экран сохраняет прежние оформления и добавляет независимый выбор уровней.
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        try {
            catalog = KanjiCatalog.load(this);
        } catch (Exception error) {
            Toast.makeText(this, "Не удалось загрузить словарь", Toast.LENGTH_LONG).show();
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(INK);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(24) + safe.left, dp(28) + safe.top,
                    dp(24) + safe.right, dp(24) + safe.bottom);
            return insets;
        });
        content.addView(label("Японские слова", 30, PAPER));
        addWithMargin(content, label("Оформление экрана блокировки", 16,
                Color.rgb(188, 202, 214)), 10);
        plain = option("Первый вариант\nСлова поверх прежних обоев");
        fuji = option("Второй вариант\nМеллстрой у Фудзи с репликой");
        addWithMargin(content, plain, 32);
        addWithMargin(content, fuji, 14);
        status = label("", 16, PAPER);
        addWithMargin(content, status, 28);

        addWithMargin(content, label("Иероглифы", 24, PAPER), 32);
        n5 = levelBox("N5", catalog == null ? 0 : catalog.countN5());
        n4 = levelBox("N4", catalog == null ? 0 : catalog.countN4());
        addWithMargin(content, n5, 12);
        content.addView(n4, fullWidth());
        auto = new CheckBox(this);
        auto.setText("Менять при включении экрана");
        auto.setTextSize(17);
        auto.setTextColor(PAPER);
        auto.setMinHeight(dp(48));
        addWithMargin(content, auto, 12);
        autoStatus = label("", 14, Color.rgb(188, 202, 214));
        addWithMargin(content, autoStatus, 4);
        current = label("", 18, PAPER);
        addWithMargin(content, current, 16);
        next = option("Следующий иероглиф");
        next.setMinHeight(dp(56));
        decorate(next, false);
        addWithMargin(content, next, 18);
        about = option("О словаре");
        about.setMinHeight(dp(56));
        decorate(about, false);
        addWithMargin(content, about, 14);

        plain.setOnClickListener(view -> changeTheme(WallpaperThemes.PLAIN));
        fuji.setOnClickListener(view -> changeTheme(WallpaperThemes.FUJI));
        n5.setOnCheckedChangeListener((button, checked) -> changeLevels());
        n4.setOnCheckedChangeListener((button, checked) -> changeLevels());
        auto.setOnCheckedChangeListener((button, checked) -> {
            if (!refreshing) {
                preferences().edit().putBoolean("auto_enabled", checked).apply();
                syncAutomatic();
                requestNotificationPermission();
                refreshSelection();
            }
        });
        next.setOnClickListener(view -> startChange(NEXT_KANJI, "next", null));
        about.setOnClickListener(view -> showAbout());
        setContentView(scroll);
        refreshSelection();
        setControlsEnabled(true);
    }

    // При возвращении в настройки восстанавливается выбранный режим после остановки системы.
    @Override
    protected void onResume() {
        super.onResume();
        syncAutomatic();
        if (auto != null) {
            refreshSelection();
        }
        requestNotificationPermission();
    }

    // Запрос нужен один раз; отказ не запрещает работу службы.
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && WakeService.isEligible(this)
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                && !preferences().getBoolean("notification_requested", false)) {
            preferences().edit().putBoolean("notification_requested", true).apply();
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 10);
        }
    }

    private void syncAutomatic() {
        autoStartFailed = !WakeService.sync(this);
        if (autoStartFailed) {
            Toast.makeText(this, "Не удалось запустить автоматическую смену", Toast.LENGTH_LONG).show();
        }
    }

    // Настройки записывает операция обновления карточки только после успешного применения.
    private void changeTheme(String theme) {
        startChange(CHANGE_THEME, "variant", theme);
    }

    private void changeLevels() {
        if (!refreshing) startChange(CHANGE_LEVELS, "levels", null);
    }

    private void startChange(int operation, String mode, String variant) {
        int widgetId = preferences().getInt("widget_id", -1);
        if (widgetId < 1) {
            refreshSelection();
            Toast.makeText(this, "Сначала добавьте виджет на экран блокировки", Toast.LENGTH_LONG).show();
            return;
        }
        setControlsEnabled(false);
        Intent request = new Intent(this, ProbeActivity.class);
        request.putExtra("mode", mode);
        request.putExtra("widget_id", widgetId);
        if (variant != null) request.putExtra("variant", variant);
        if (operation == CHANGE_LEVELS) {
            request.putExtra("n5_enabled", n5.isChecked());
            request.putExtra("n4_enabled", n4.isChecked());
        }
        startActivityForResult(request, operation);
    }

    // После любого результата экран показывает только действительно сохранённое состояние.
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request < CHANGE_THEME || request > NEXT_KANJI) return;
        syncAutomatic();
        requestNotificationPermission();
        refreshSelection();
        setControlsEnabled(true);
        String message;
        if (request == CHANGE_THEME) {
            message = result == RESULT_OK ? "Оформление включено" : "Не удалось сменить оформление";
        } else if (request == CHANGE_LEVELS) {
            message = result == RESULT_OK ? "Выбор уровней сохранён" : "Не удалось изменить уровни";
        } else {
            message = result == RESULT_OK ? "Иероглиф обновлён" : "Не удалось сменить иероглиф";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void refreshSelection() {
        SharedPreferences saved = preferences();
        boolean selectedFuji = WallpaperThemes.FUJI.equals(saved.getString("variant", WallpaperThemes.PLAIN));
        decorate(plain, !selectedFuji);
        decorate(fuji, selectedFuji);
        status.setText(selectedFuji ? "Сейчас: Меллстрой у Фудзи" : "Сейчас: прозрачные слова");
        refreshing = true;
        n5.setChecked(saved.getBoolean("n5_enabled", true));
        n4.setChecked(saved.getBoolean("n4_enabled", true));
        auto.setChecked(saved.getBoolean("auto_enabled", true));
        refreshing = false;
        boolean enabled = n5.isChecked() || n4.isChecked();
        if (autoStartFailed && auto.isChecked() && enabled) {
            autoStatus.setText("Не удалось запустить смену. Откройте приложение ещё раз");
        } else if (!auto.isChecked()) {
            autoStatus.setText("Автоматическая смена выключена");
        } else if (saved.getInt("widget_id", -1) < 1) {
            autoStatus.setText("Добавьте виджет, чтобы включить смену");
        } else if (!enabled) {
            autoStatus.setText("Выберите хотя бы один уровень, чтобы возобновить смену");
        } else {
            autoStatus.setText("Следующий иероглиф появится при включении экрана");
        }
        KanjiCatalog.Entry entry = catalog == null ? null : catalog.find(saved.getString("kanji", ""));
        if (!enabled) {
            current.setText("Показ иероглифов выключен");
        } else if (entry != null) {
            current.setText(entry.kanji + " · " + entry.level + "\nЧтения: " + entry.reading + "\n" + entry.meaning);
        } else {
            current.setText(saved.getString("kanji", "") + "\nЧтения: "
                    + saved.getString("reading", "") + "\n" + saved.getString("meaning", ""));
        }
        next.setEnabled(enabled && catalog != null);
    }

    private void setControlsEnabled(boolean enabled) {
        plain.setEnabled(enabled);
        fuji.setEnabled(enabled);
        n5.setEnabled(enabled && catalog != null);
        n4.setEnabled(enabled && catalog != null);
        auto.setEnabled(enabled);
        next.setEnabled(enabled && catalog != null && (n5.isChecked() || n4.isChecked()));
        about.setEnabled(enabled);
    }

    // Источники и лицензия доступны прямо из настроек словаря.
    private void showAbout() {
        TextView text = label("Данные OpenJLPT; чтения и исходные значения — KANJIDIC2, группа EDRDG. Уровни — списки Джонатана Уоллера с дополнениями OpenJLPT. Русские значения сокращены и переведены. Списки уровней неофициальные.\n\nOpenJLPT: https://github.com/evanclan/OpenJLPT\nKANJIDIC2: https://www.edrdg.org/wiki/KANJIDIC_Project.html\nЛицензия CC BY-SA 4.0: https://creativecommons.org/licenses/by-sa/4.0/", 16, PAPER);
        text.setPadding(dp(24), dp(12), dp(24), dp(12));
        text.setBackgroundColor(INK);
        text.setLinkTextColor(Color.rgb(152, 213, 255));
        Linkify.addLinks(text, Linkify.WEB_URLS);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new AlertDialog.Builder(this).setTitle("О словаре").setView(scroll)
                .setPositiveButton("Закрыть", null).show();
    }

    private CheckBox levelBox(String level, int count) {
        CheckBox box = new CheckBox(this);
        box.setText(level + " — " + count);
        box.setTextSize(17);
        box.setTextColor(PAPER);
        box.setMinHeight(dp(48));
        return box;
    }

    private void addWithMargin(LinearLayout content, android.view.View view, int margin) {
        LinearLayout.LayoutParams layout = fullWidth();
        layout.topMargin = dp(margin);
        content.addView(view, layout);
    }

    private Button option(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(20), dp(18), dp(20), dp(18));
        button.setMinHeight(dp(100));
        return button;
    }

    private void decorate(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(selected ? PAPER : Color.rgb(35, 51, 66));
        background.setStroke(dp(1), selected ? PAPER : Color.rgb(75, 94, 110));
        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setTextColor(selected ? INK : PAPER);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("appearance", MODE_PRIVATE);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
