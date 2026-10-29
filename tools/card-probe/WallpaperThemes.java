package ru.local.nihongo.probe;

import android.app.WallpaperManager;
import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Меняет только фон блокировки; сохранённые исходные обои не перезаписывает. */
final class WallpaperThemes {
    static final String PLAIN = "plain";
    static final String FUJI = "fuji";

    private WallpaperThemes() { }

    // Первый фон заранее сохранён отладкой; второй хранится в ресурсах приложения.
    static void apply(Context context, String theme) throws Exception {
        WallpaperManager manager = WallpaperManager.getInstance(context);
        if (FUJI.equals(theme)) {
            try (InputStream image = context.getAssets().open("mellstroy-fuji-v2.png")) {
                manager.setStream(image, null, true, WallpaperManager.FLAG_LOCK);
            }
        } else if (PLAIN.equals(theme)) {
            File original = new File(context.getFilesDir(), "wallpapers/plain.img");
            if (!original.isFile()) {
                throw new IllegalStateException("Исходный фон первого варианта не сохранён");
            }
            try (InputStream image = new FileInputStream(original)) {
                manager.setStream(image, null, true, WallpaperManager.FLAG_LOCK);
            }
        } else {
            throw new IllegalArgumentException("Неизвестное оформление");
        }
    }
}
