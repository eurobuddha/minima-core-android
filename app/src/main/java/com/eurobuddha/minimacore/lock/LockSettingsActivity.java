package com.eurobuddha.minimacore.lock;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.eurobuddha.minimacore.R;
import com.eurobuddha.minimacore.utils.KeyboardInsets;

import java.util.function.Consumer;

/** "App Lock" settings: turn the PIN gate on/off, add biometric, change the PIN, set the auto-lock delay. */
public class LockSettingsActivity extends AppCompatActivity {

    private SwitchMaterial mEnable, mBio;
    private View mOptions;
    private MaterialButton mAutolock, mChangePin;

    private final CompoundButton.OnCheckedChangeListener mEnableListener =
            (b, on) -> onToggleEnable(on);
    private final CompoundButton.OnCheckedChangeListener mBioListener =
            (b, on) -> onToggleBio(on);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_settings);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("App Lock");
        setSupportActionBar(tb);
        KeyboardInsets.install(this, findViewById(R.id.locksettings_main), tb);

        mEnable    = findViewById(R.id.lock_switch_enable);
        mBio       = findViewById(R.id.lock_switch_bio);
        mOptions   = findViewById(R.id.lock_options);
        mAutolock  = findViewById(R.id.lock_autolock);
        mChangePin = findViewById(R.id.lock_change_pin);

        mAutolock.setOnClickListener(v -> pickAutolock());
        mChangePin.setOnClickListener(v -> changePin());

        render();
    }

    /** Reflect the actual stored state; set switch values WITHOUT firing their listeners. */
    private void render() {
        bind(mEnable, AppLock.isEnabled(this), mEnableListener);
        mOptions.setVisibility(AppLock.isEnabled(this) ? View.VISIBLE : View.GONE);
        mBio.setVisibility(BiometricUnlock.isAvailable(this) ? View.VISIBLE : View.GONE);
        bind(mBio, BiometricUnlock.isEnabled(this), mBioListener);
        mAutolock.setText(autolockLabel());
    }

    private static void bind(SwitchMaterial sw, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
    }

    // --- enable / disable the whole lock ---------------------------------------------------------

    private void onToggleEnable(boolean on) {
        if (on) {
            if (AppLock.isEnabled(this)) return;
            promptNewPin(pin -> {
                AppLock.setPin(this, pin);
                AppLock.get().unlock();   // we're in-app and just set it — don't lock ourselves out now
                toast("App lock on");
                render();
            }, this::render);             // cancelled → revert the switch
        } else {
            if (!AppLock.isEnabled(this)) return;
            promptPin("Enter your PIN to turn off the lock", "Turn off", pin -> {
                if (!AppLock.verifyPin(this, pin)) { toast("Wrong PIN"); render(); return; }
                AppLock.disable(this);
                toast("App lock off");
                render();
            });
        }
    }

    // --- biometric ------------------------------------------------------------------------------

    private void onToggleBio(boolean on) {
        if (on) {
            promptPin("Enter your PIN to enable biometric unlock", "Enable", pin -> {
                if (!AppLock.verifyPin(this, pin)) { toast("Wrong PIN"); render(); return; }
                BiometricUnlock.enable(this, pin, new BiometricUnlock.Callback() {
                    @Override public void onSuccess() { toast("Biometric unlock on"); render(); }
                    @Override public void onError(String m, boolean inv) { toast(m); render(); }
                    @Override public void onCancelled() { render(); }
                });
            });
        } else {
            BiometricUnlock.disable(this);
            render();
        }
    }

    // --- change PIN ------------------------------------------------------------------------------

    private void changePin() {
        promptPin("Enter your current PIN", "Next", current -> {
            if (!AppLock.verifyPin(this, current)) { toast("Wrong PIN"); return; }
            promptNewPin(newPin -> {
                AppLock.setPin(this, newPin);
                // Any biometric entry wrapped the OLD PIN, so it would hand back a stale value — clear it.
                if (BiometricUnlock.isEnabled(this)) {
                    BiometricUnlock.disable(this);
                    toast("PIN changed — re-enable biometric unlock with your new PIN");
                } else {
                    toast("PIN changed");
                }
                render();
            }, () -> {});
        });
    }

    // --- auto-lock delay -------------------------------------------------------------------------

    private void pickAutolock() {
        int sel = indexOfMinutes(AppLock.autolockMinutes(this));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Auto-lock")
                .setSingleChoiceItems(AppLock.AUTOLOCK_NAMES, sel, (d, which) -> {
                    AppLock.setAutolockMinutes(this, AppLock.AUTOLOCK_MINUTES[which]);
                    d.dismiss();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String autolockLabel() {
        return AppLock.AUTOLOCK_NAMES[indexOfMinutes(AppLock.autolockMinutes(this))];
    }

    private static int indexOfMinutes(int minutes) {
        for (int i = 0; i < AppLock.AUTOLOCK_MINUTES.length; i++) {
            if (AppLock.AUTOLOCK_MINUTES[i] == minutes) return i;
        }
        return 1;   // default: "After 1 minute"
    }

    // --- PIN dialogs -----------------------------------------------------------------------------

    /** One PIN field; {@code onEntered} fires with the typed PIN only when it meets the length rule. */
    private void promptPin(String title, String positive, Consumer<String> onEntered) {
        final EditText in = pinField("PIN");
        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(pad(in))
                .setPositiveButton(positive, null)
                .setNegativeButton("Cancel", (dd, w) -> render())
                .setOnCancelListener(dd -> render())
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = in.getText().toString();
            if (pin.length() < AppLock.MIN_PIN_LEN) {
                in.setError("At least " + AppLock.MIN_PIN_LEN + " digits");
                return;
            }
            d.dismiss();
            onEntered.accept(pin);
        }));
        d.show();
    }

    /** Two PIN fields (enter + confirm); {@code onSet} fires only when they are valid and match. */
    private void promptNewPin(Consumer<String> onSet, Runnable onCancel) {
        final EditText p1 = pinField("New PIN (" + AppLock.MIN_PIN_LEN + "–" + AppLock.MAX_PIN_LEN + " digits)");
        final EditText p2 = pinField("Confirm PIN");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = dp(20);
        col.setPadding(side, dp(8), side, 0);
        col.addView(p1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        col.addView(p2, lp);

        AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle("Set a PIN")
                .setView(col)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", (dd, w) -> onCancel.run())
                .setOnCancelListener(dd -> onCancel.run())
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString();
            String b = p2.getText().toString();
            if (a.length() < AppLock.MIN_PIN_LEN || a.length() > AppLock.MAX_PIN_LEN) {
                p1.setError(AppLock.MIN_PIN_LEN + "–" + AppLock.MAX_PIN_LEN + " digits");
                return;
            }
            if (!a.equals(b)) {
                p2.setError("PINs do not match");
                return;
            }
            d.dismiss();
            onSet.accept(a);
        }));
        d.show();
    }

    private EditText pinField(String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(AppLock.MAX_PIN_LEN) });
        e.setHint(hint);
        return e;
    }

    private LinearLayout pad(View child) {
        LinearLayout wrap = new LinearLayout(this);
        int side = dp(20);
        wrap.setPadding(side, dp(8), side, 0);
        wrap.addView(child, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
