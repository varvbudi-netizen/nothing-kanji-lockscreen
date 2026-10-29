package ru.local.nihongo.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Общая очередь операций с одной системной карточкой. */
final class CardController {
    private static final String TAG = "NihongoProbe";
    private static final String SERVICE_PACKAGE = "com.nothing.cardservice";
    private static final String DESCRIPTOR = "com.nothing.cardservice.ICardWidgetService";
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Intent request;
    private volatile IBinder service;

    private CardController(Context context, Intent request) {
        this.context = context.getApplicationContext();
        this.request = request;
    }

    // Ручные и автоматические операции проходят через одну очередь.
    static void submit(Context context, Intent request, java.util.function.Consumer<Boolean> callback) {
        CardController controller = new CardController(context, new Intent(request));
        QUEUE.execute(() -> {
            boolean success = false;
            try {
                controller.execute();
                success = true;
            } catch (Exception error) {
                Log.e(TAG, "Операция с карточкой не выполнена", error);
            }
            boolean result = success;
            if (callback != null) {
                MAIN.post(() -> callback.accept(result));
            }
        });
    }

    // Соединение ограничено по времени и освобождается после каждой операции.
    private void execute() throws Exception {
        if ("restore".equals(request.getStringExtra("mode"))) {
            WakeService.disable(context);
        }
        if ("wake".equals(request.getStringExtra("mode")) && !WakeService.isEligible(context)) {
            return;
        }
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = binder;
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
            }
            @Override public void onNullBinding(ComponentName name) {
                connected.countDown();
            }
            @Override public void onBindingDied(ComponentName name) {
                connected.countDown();
            }
        };
        Intent intent = new Intent("com.nothing.cardservice.BIND_SERVICE");
        intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_PACKAGE + ".CardWidgetService"));
        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            throw new IllegalStateException("Служба карточек недоступна");
        }
        try {
            if (!connected.await(5, TimeUnit.SECONDS) || service == null) {
                throw new IllegalStateException("Служба карточек не ответила");
            }
            runExperiment();
        } finally {
            context.unbindService(connection);
        }
    }

    // Номера операций и порядок полей восстановлены из установленной версии 4.1.6.
    private JSONObject readCard(int widgetId) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(widgetId);
            if (!service.transact(12, request, response, 0)) {
                throw new IllegalStateException("Операция чтения карточки отсутствует");
            }
            response.readException();
            if (response.readInt() == 0) {
                throw new IllegalStateException("Выбранная карточка не найдена");
            }
            JSONObject card = new JSONObject();
            card.put("widget_id", response.readInt());
            card.put("card_id", response.readInt());
            card.put("host_id", response.readInt());
            card.put("card_info", response.readString());
            card.put("user_id", response.readInt());
            card.put("ext1", response.readString());
            card.put("ext2", response.readString());
            card.put("ext3", response.readString());
            return card;
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    // Сохранение исходного содержимого обязательно до любого изменяющего опыта.
    private void runExperiment() throws Exception {
        int widgetId = "wake".equals(request.getStringExtra("mode"))
                ? WakeService.preferences(context).getInt("widget_id", -1)
                : request.getIntExtra("widget_id", -1);
        if (widgetId < 1) {
            throw new IllegalArgumentException("Нужно явно указать существующую карточку");
        }
        String mode = request.getStringExtra("mode");
        if ("wake".equals(mode) && !WakeService.isEligible(context)) {
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
        File backup = new File(context.getFilesDir(), "widget-" + widgetId + ".json");
        if ("restore".equals(mode) && !backup.isFile()) {
            throw new IllegalStateException("Резервная копия карточки не найдена");
        }
        JSONObject current = readCard(widgetId);
        // Собственный макет не является исходной карточкой и не годится для восстановления.
        if (!backup.exists()) {
            JSONObject content = new JSONObject(current.getString("card_info"));
            JSONObject config = new JSONObject(content.getString("config_info"));
            if (!context.getPackageName().equals(config.optString("package_name"))) {
                Files.write(backup.toPath(), current.toString(2).getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
        File snapshot = new File(context.getFilesDir(), "current-widget-" + widgetId + ".json");
        Files.write(snapshot.toPath(), current.toString(2).getBytes(StandardCharsets.UTF_8));
        Log.i(TAG, "Карточка прочитана: widget=" + widgetId
                + ", card=" + current.getInt("card_id")
                + ", host=" + current.getInt("host_id")
                + ", сохранение=" + backup.exists());

        String[] words = readWords(current, preferences);
        if ("apply".equals(mode) || "variant".equals(mode)
                || "levels".equals(mode) || "next".equals(mode) || "wake".equals(mode)) {
            // Выключенные уровни исключаются из набора; пустой набор скрывает всю реплику.
            boolean n5 = preferences.getBoolean("n5_enabled", true);
            boolean n4 = preferences.getBoolean("n4_enabled", true);
            if ("levels".equals(mode)) {
                n5 = request.getBooleanExtra("n5_enabled", n5);
                n4 = request.getBooleanExtra("n4_enabled", n4);
            }
            KanjiCatalog catalog = KanjiCatalog.load(context);
            KanjiCatalog.Entry entry = catalog.next(n5, n4, words[0], "next".equals(mode) || "wake".equals(mode));
            if ("wake".equals(mode) && entry == null) {
                return;
            }
            boolean showWord = entry != null;
            if (showWord) {
                words = new String[] {entry.kanji, entry.widgetReading, entry.meaning};
            }
            String oldVariant = preferences.getString("variant", WallpaperThemes.PLAIN);
            String selectedVariant = "variant".equals(mode) ? request.getStringExtra("variant") : oldVariant;
            JSONObject beforeCard = new JSONObject(current.toString());
            JSONObject config = new JSONObject(new JSONObject(current.getString("card_info")).getString("config_info"));
            config.put("package_name", context.getPackageName());
            config.put("authority", context.getPackageName());
            config.put("grant_authority", context.getPackageName() + ".ntwidget.filepermissionprovider");
            config.put("layout_id", !showWord ? R.layout.word_hidden
                    : WallpaperThemes.FUJI.equals(selectedVariant) ? R.layout.word_speech : R.layout.word);
            JSONObject content = new JSONObject();
            content.put("config_info", config.toString());
            JSONArray views = new JSONArray();
            if (showWord) {
                views.put(textView(R.id.kanji, words[0]));
                views.put(textView(R.id.reading, words[1]));
                views.put(textView(R.id.meaning, words[2]));
            }
            content.put("view_info", views);
            content.put("action_info", new JSONArray());
            content.put("custom_info", new JSONArray());
            current.put("card_info", content.toString());
            boolean changeWallpaper = "variant".equals(mode);
            if (changeWallpaper) {
                WallpaperThemes.apply(context, selectedVariant);
            }
            try {
                updateStableCard(current, changeWallpaper);
            } catch (Exception error) {
                try {
                    if (changeWallpaper) {
                        WallpaperThemes.apply(context, oldVariant);
                    }
                    updateStableCard(beforeCard, changeWallpaper);
                } catch (Exception restoreError) {
                    error.addSuppressed(restoreError);
                }
                throw error;
            }
            // Настройки становятся действующими только после подтверждения системной карточки.
            preferences.edit().putInt("widget_id", widgetId)
                    .putString("variant", selectedVariant)
                    .putString("kanji", words[0]).putString("reading", words[1])
                    .putString("meaning", words[2])
                    .putBoolean("n5_enabled", n5).putBoolean("n4_enabled", n4).commit();
            // Пустой набор сразу останавливает ожидание независимо от открытого окна.
            if (!WakeService.isEligible(context)) {
                context.stopService(new Intent(context, WakeService.class));
            }
            Log.i(TAG, "Оформление: " + selectedVariant + ", N5=" + n5
                    + ", N4=" + n4 + ", знак=" + (showWord ? words[0] : "скрыт"));
        } else if ("restore".equals(mode)) {
            String saved = new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8);
            updateStableCard(new JSONObject(saved), false);
        } else if ("inspect".equals(mode)) {
            preferences.edit().putInt("widget_id", widgetId)
                    .putString("kanji", words[0]).putString("reading", words[1])
                    .putString("meaning", words[2]).apply();
        } else {
            throw new IllegalArgumentException("Неизвестный режим опыта");
        }
    }

    // Смена обоев вызывает запоздалое обновление фотовиджета: результат подтверждается чтением службы.
    private void updateStableCard(JSONObject card, boolean wallpaperChanged) throws Exception {
        String expected = card.getString("card_info");
        int widgetId = card.getInt("widget_id");
        long started = SystemClock.elapsedRealtime();
        long stableSince = 0;
        updateCard(card);
        while (SystemClock.elapsedRealtime() - started < 6000) {
            Thread.sleep(120);
            long now = SystemClock.elapsedRealtime();
            if (expected.equals(readCard(widgetId).getString("card_info"))) {
                if (stableSince == 0) {
                    stableSince = now;
                }
                if (now - stableSince >= 600 && (!wallpaperChanged || now - started >= 2000)) {
                    Log.i(TAG, "Содержимое подтверждено повторным чтением службы");
                    return;
                }
            } else {
                stableSince = 0;
                Log.i(TAG, "Штатная карточка обновилась после смены фона; возвращаем выбранное оформление");
                updateCard(card);
            }
        }
        throw new IllegalStateException("Служба не сохранила выбранное оформление");
    }

    // Переключение оформления переносит последнее слово из живой карточки, даже после обновления приложения.
    private String[] readWords(JSONObject current, SharedPreferences preferences) throws Exception {
        String[] keys = {"kanji", "reading", "meaning"};
        String[] words = {preferences.getString("kanji", "北"),
                preferences.getString("reading", "きた · kita"), preferences.getString("meaning", "север")};
        JSONObject content = new JSONObject(current.getString("card_info"));
        JSONObject config = new JSONObject(content.getString("config_info"));
        JSONArray views = content.optJSONArray("view_info");
        if (context.getPackageName().equals(config.optString("package_name")) && views != null && views.length() == 3) {
            for (int index = 0; index < words.length; index++) {
                JSONObject view = new JSONObject(views.getString(index));
                words[index] = new JSONObject(view.getString("setText")).getString("param_value");
            }
        }
        for (int index = 0; index < words.length; index++) {
            words[index] = wordExtra(keys[index], words[index]);
        }
        return words;
    }

    // Формат текстовых свойств восстановлен из клиента Nothing, включая строковую упаковку.
    private String textView(int viewId, String text) throws Exception {
        JSONObject parameter = new JSONObject();
        parameter.put("item_type", 5);
        parameter.put("param_type", 2);
        parameter.put("param_value", text);
        JSONObject view = new JSONObject();
        view.put("item_type", 1);
        view.put("view_id", viewId);
        view.put("view_type", "textview");
        view.put("setText", parameter.toString());
        return view.toString();
    }

    // Опыт меняет данные без переустановки макета и без обращения к сети.
    private String wordExtra(String key, String fallback) {
        String value = request.getStringExtra(key);
        return value == null ? fallback : value;
    }

    // Передаются только поля выбранного виджета; другие карточки не затрагиваются.
    private void updateCard(JSONObject card) throws Exception {
        Bundle extras = new Bundle();
        for (String key : new String[] {"widget_id", "card_id", "host_id", "user_id"}) {
            extras.putInt(key, card.getInt(key));
        }
        for (String key : new String[] {"card_info", "ext1", "ext2", "ext3"}) {
            if (card.has(key) && !card.isNull(key)) {
                extras.putString(key, card.getString(key));
            }
        }
        context.getContentResolver().call(SERVICE_PACKAGE, "updateAppWidget",
                Integer.toString(card.getInt("widget_id")), extras);
        Log.i(TAG, "Запрос обновления отправлен; отображение ещё нужно проверить на телефоне");
    }

}
