package ru.local.nihongo.probe;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Словарь отдельных иероглифов и последовательный выбор из включённых уровней. */
public final class KanjiCatalog {
    public static final class Entry {
        public final String kanji, reading, widgetReading, meaning, level;

        private Entry(String kanji, String reading, String widgetReading, String meaning, String level) {
            this.kanji = kanji;
            this.reading = reading;
            this.widgetReading = widgetReading;
            this.meaning = meaning;
            this.level = level;
        }
    }

    private final List<Entry> n5 = new ArrayList<>();
    private final List<Entry> n4 = new ArrayList<>();

    // Файлы входят в приложение и читаются один раз при создании каталога.
    public static KanjiCatalog load(Context context) throws Exception {
        KanjiCatalog catalog = new KanjiCatalog();
        catalog.readLevel(context, "n5.json", "N5", catalog.n5);
        catalog.readLevel(context, "n4.json", "N4", catalog.n4);
        return catalog;
    }

    private void readLevel(Context context, String file, String level, List<Entry> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = context.getAssets().open(file)) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, length);
            }
        }
        JSONArray data = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            JSONArray on = item.getJSONArray("onyomi");
            JSONArray kun = item.getJSONArray("kunyomi");
            List<String> readings = new ArrayList<>();
            List<String> shortReadings = new ArrayList<>();
            for (JSONArray group : new JSONArray[] {on, kun}) {
                for (int j = 0; j < group.length(); j++) {
                    String reading = group.getString(j);
                    readings.add(reading);
                    if (j == 0) {
                        shortReadings.add(reading);
                    }
                }
            }
            entries.add(new Entry(item.getString("kanji"), String.join("・", readings),
                    String.join("\n", shortReadings), item.getString("meaning_ru"), level));
        }
    }

    public int countN5() { return n5.size(); }
    public int countN4() { return n4.size(); }

    // Список формируется в стабильном порядке, поэтому полный обход не повторяет знаки.
    public List<Entry> pool(boolean n5Enabled, boolean n4Enabled) {
        List<Entry> result = new ArrayList<>();
        if (n5Enabled) result.addAll(n5);
        if (n4Enabled) result.addAll(n4);
        return result;
    }

    public Entry find(String kanji) {
        for (Entry entry : pool(true, true)) {
            if (entry.kanji.equals(kanji)) return entry;
        }
        return null;
    }

    public Entry next(boolean n5Enabled, boolean n4Enabled, String current, boolean advance) {
        List<Entry> entries = pool(n5Enabled, n4Enabled);
        if (entries.isEmpty()) return null;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kanji.equals(current)) {
                return entries.get(advance ? (i + 1) % entries.size() : i);
            }
        }
        return entries.get(0);
    }
}
