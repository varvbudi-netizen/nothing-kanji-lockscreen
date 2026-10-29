package ru.local.nihongo.probe;

import android.app.Activity;
import android.os.Bundle;

/** Короткое окно для ручного изменения карточки. */
public final class ProbeActivity extends Activity {
    private Operation operation;

    // Результат остаётся с операцией при пересоздании окна, а не запускает её повторно.
    private static final class Operation {
        private ProbeActivity activity;
        private Boolean result;

        void attach(ProbeActivity owner) {
            activity = owner;
            if (result != null) {
                deliver();
            }
        }

        void detach(ProbeActivity owner) {
            if (activity == owner) {
                activity = null;
            }
        }

        void complete(boolean success) {
            result = success;
            deliver();
        }

        private void deliver() {
            if (activity != null && result != null) {
                activity.setResult(result ? RESULT_OK : RESULT_CANCELED);
                activity.finish();
                activity = null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        operation = (Operation) getLastNonConfigurationInstance();
        if (operation != null) {
            operation.attach(this);
            return;
        }
        // После гибели процесса нельзя безопасно повторять уже начатую смену.
        if (state != null && state.getBoolean("started", false)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        if ("restore".equals(getIntent().getStringExtra("mode"))) {
            WakeService.disable(this);
        }
        operation = new Operation();
        operation.attach(this);
        Operation pending = operation;
        CardController.submit(this, getIntent(), pending::complete);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("started", operation != null);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return operation;
    }

    @Override
    protected void onDestroy() {
        if (operation != null) {
            operation.detach(this);
        }
        super.onDestroy();
    }
}
