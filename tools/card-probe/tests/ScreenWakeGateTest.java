package ru.local.nihongo.probe;

/** Проверяет пользовательские последовательности событий без телефона и библиотек Android. */
public final class ScreenWakeGateTest {
    public static void main(String[] args) {
        firstObservedWake();
        repeatedSystemEvents();
        startOnDarkScreen();
        manyWakeCycles();
        registrationDuringWake();
        System.out.println("Проверено пять сценариев пробуждения, включая 10 000 циклов экрана");
    }

    // Первое настоящее событие принимается, даже если предшествующее выключение не наблюдалось.
    private static void firstObservedWake() {
        ScreenWakeGate gate = new ScreenWakeGate();
        require(gate.onScreenOn(), "Первое полученное пробуждение должно менять знак");
        require(!gate.onScreenOn(), "Дубль первого события не должен менять знак");
        gate.onScreenOff();
        require(gate.onScreenOn(), "После выключения нужен следующий знак");
        require(!gate.onScreenOn(), "Одно пробуждение не должно перелистывать дважды");
    }

    // Дубли системных событий не увеличивают число смен в одном цикле.
    private static void repeatedSystemEvents() {
        ScreenWakeGate gate = new ScreenWakeGate();
        gate.onScreenOff();
        gate.onScreenOff();
        require(gate.onScreenOn(), "Повторное выключение не должно терять пробуждение");
        require(!gate.onScreenOn(), "Повторное включение должно быть проигнорировано");
        gate.onScreenOff();
        require(gate.onScreenOn(), "Следующий отдельный цикл должен работать");
    }

    // Восстановленная при погасшем экране служба принимает ближайшее пробуждение.
    private static void startOnDarkScreen() {
        ScreenWakeGate gate = new ScreenWakeGate();
        gate.onScreenOff();
        require(gate.onScreenOn(), "Нельзя пропускать пробуждение после запуска в темноте");
        require(!gate.onScreenOn(), "После первого пробуждения защита от дублей сохраняется");
    }

    // Длительная последовательность проверяет отсутствие накопления лишних смен.
    private static void manyWakeCycles() {
        ScreenWakeGate gate = new ScreenWakeGate();
        int changes = 0;
        for (int cycle = 0; cycle < 10_000; cycle++) {
            gate.onScreenOff();
            gate.onScreenOff();
            if (gate.onScreenOn()) {
                changes++;
            }
            require(!gate.onScreenOn(), "Дубль включения в цикле " + cycle);
        }
        require(changes == 10_000, "Каждый полный цикл должен давать ровно одну смену");
    }

    // Подписка могла произойти между выключением и доставкой включения: снимок состояния не нужен.
    private static void registrationDuringWake() {
        ScreenWakeGate afterRegistration = new ScreenWakeGate();
        require(afterRegistration.onScreenOn(), "Пробуждение в момент подписки нельзя терять");
        require(!afterRegistration.onScreenOn(), "Повторное включение после подписки игнорируется");
        afterRegistration.onScreenOff();
        require(afterRegistration.onScreenOn(), "Следующий полный цикл работает после подписки");
    }

    // Проверки не зависят от флага включения стандартных утверждений Java.
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
