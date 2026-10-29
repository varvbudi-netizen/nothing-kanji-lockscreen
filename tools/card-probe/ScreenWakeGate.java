package ru.local.nihongo.probe;

/** Разрешает ровно одну смену после каждого выключения экрана. */
final class ScreenWakeGate {
    // Начальное состояние неизвестно: первое реальное включение принимается.
    private boolean armed = true;

    ScreenWakeGate() {
    }

    void onScreenOff() {
        armed = true;
    }

    boolean onScreenOn() {
        if (!armed) {
            return false;
        }
        armed = false;
        return true;
    }
}
