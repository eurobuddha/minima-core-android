package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Public fixtures only, on the owned disposable emulator with no node or seed. */
@RunWith(AndroidJUnit4.class)
public class DualKeyAuditScreenTest {
    private static final String CLASSIC = "0x3A985DA74FE225B2045C172D6BD390BD855F086E3E9D525B46BFE24511431532";
    private static final String BLOCK = "0xA7FFC6F8BF1ED76651C14756A061D662F580FF4DE43B49FA82D80A4B80F8434A";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before public void prepare() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu", Build.HARDWARE);
        assertNull("Requires a stopped disposable node", MinimaService.minima);
        context.getSharedPreferences("futurecash_guardian", Context.MODE_PRIVATE).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppLock.disable(context);
            Language.select(context, "en");
        });
    }

    private static Field field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static String card(MainActivity a, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        StringBuilder text = new StringBuilder();
        collect((View) method.invoke(a), text);
        return text.toString();
    }
    private static void collect(View view, StringBuilder text) {
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), text);
        }
    }
    private static KeyAudit.Result result(boolean complete, int classicUses) {
        MinimaAddress.Result classic = MinimaAddress.fromPublicKey(CLASSIC);
        MinimaAddress.Result block = MinimaAddress.fromPublicKey(BLOCK);
        return KeyAudit.join(Arrays.asList(
                new KeyAudit.LocalKey(CLASSIC, classicUses, 262144, 64, 3),
                new KeyAudit.LocalKey(BLOCK, 2350000, 268435456, 128, 4)),
                Arrays.asList(new KeyAudit.Usage(CLASSIC, classic.hex, 10, 10),
                        new KeyAudit.Usage(BLOCK, block.hex, 2, 2)),
                complete ? Arrays.asList(new KeyAudit.Reuse(classic.hex, false, 0),
                        new KeyAudit.Reuse(block.hex, false, 0))
                        : Collections.singletonList(new KeyAudit.Reuse(classic.hex, false, 0)),
                Arrays.asList(classic, block));
    }

    @Test public void summaryAndDetailsDistinguishBothFamiliesAndIncompleteEvidence() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            // Finish the offline startup callbacks before supplying deterministic public fixtures.
            android.os.SystemClock.sleep(1200);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> {
                try {
                    ((ExecutorService) field("worker").get(a)).shutdownNow();
                    ((AuditRunner) field("auditRunner").get(a)).onDestroy();
                    Cfg cfg = (Cfg) field("cfg").get(a);
                    cfg.set(Cfg.AUDIT_AT, String.valueOf(System.currentTimeMillis()));
                    cfg.set(Cfg.AUDIT_LAST_ERR, "");
                    field("auditRunning").set(a, false);
                    field("lastVerdict").set(a, new Audit.Verdict(false, System.currentTimeMillis(), Audit.Mode.PARTIAL));
                    field("lastAudit").set(a, result(false, 12));
                    String details = card(a, "keyTableCard");
                    assertTrue(details, details.contains("Classic keys: 1 · Block keys: 1"));
                    assertTrue(details, details.contains("Classic · counter 12"));
                    assertTrue(details, details.contains("Block · leaf position 2350000"));
                    assertTrue(details, details.contains("Not fully checked"));
                    String partial = card(a, "auditCard");
                    assertTrue(partial, partial.contains("audit is incomplete or out of date"));
                    assertFalse(partial, partial.contains("No known key reuse"));

                    field("lastVerdict").set(a, new Audit.Verdict(true, System.currentTimeMillis(), Audit.Mode.FRESH));
                    field("lastAudit").set(a, result(true, 2));
                    assertTrue(card(a, "auditCard").contains("Key-reuse risk is recorded"));

                    field("lastAudit").set(a, null); // A newly opened screen must retain stored warnings.
                    cfg.set(Cfg.ATRISK_ADDRS, "[\"" + MinimaAddress.fromPublicKey(CLASSIC).hex + "\"]");
                    assertTrue(card(a, "auditCard").contains("Key-reuse risk is recorded"));

                    cfg.set(Cfg.ATRISK_ADDRS, "[]");
                    field("lastAudit").set(a, result(true, 12));
                    assertTrue(card(a, "auditCard").contains("No known key reuse was found in the checked keys"));
                    assertFalse(card(a, "keyTableCard").contains("Not fully checked"));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            assertNull("Audit display checks must not start a node", MinimaService.minima);
        } finally {
            context.getSharedPreferences("futurecash_guardian", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
