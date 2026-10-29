package ru.local.nihongo.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

/** Ожидает системные события экрана без периодического опроса. */
public final class WakeService extends Service {
    private static final String CHANNEL = "screen_changes";
    private static final String STOP = "ru.local.nihongo.probe.STOP_AUTO";
    private ScreenWakeGate gate;
    private boolean registered;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent event) {
            if (Intent.ACTION_SCREEN_OFF.equals(event.getAction())) {
                gate.onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(event.getAction()) && gate.onScreenOn()) {
                Intent request = new Intent();
                request.putExtra("mode", "wake");
                request.putExtra("widget_id", preferences(context).getInt("widget_id", -1));
                CardController.submit(context, request, null);
            }
        }
    };

    // Служба работает только при сохранённом выборе и непустом наборе уровней.
    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    static boolean isEligible(Context context) {
        SharedPreferences saved = preferences(context);
        return saved.getBoolean("auto_enabled", true) && saved.getInt("widget_id", -1) > 0
                && (saved.getBoolean("n5_enabled", true) || saved.getBoolean("n4_enabled", true));
    }

    static boolean sync(Context context) {
        if (isEligible(context)) {
            try {
                context.startForegroundService(new Intent(context, WakeService.class));
                return true;
            } catch (RuntimeException error) {
                Log.e("NihongoProbe", "Не удалось запустить автоматическую смену", error);
                return false;
            }
        }
        context.stopService(new Intent(context, WakeService.class));
        return true;
    }

    static void disable(Context context) {
        preferences(context).edit().putBoolean("auto_enabled", false).apply();
        context.stopService(new Intent(context, WakeService.class));
    }

    // Уведомление даёт доступ к настройкам и позволяет остановить службу.
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Смена иероглифов",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent settings = PendingIntent.getActivity(this, 0,
                new Intent(this, ThemeActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, WakeService.class).setAction(STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Смена иероглифов включена")
                .setContentText("При включении экрана появится следующий иероглиф")
                .setContentIntent(settings)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Выключить", stopAction).build())
                .build();
        startForeground(1, notification);
        // Запуск службы сам по себе не создаёт событие включения экрана.
        gate = new ScreenWakeGate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
        registered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            disable(this);
            return START_NOT_STICKY;
        }
        if (!isEligible(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (registered) {
            unregisterReceiver(screenReceiver);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
