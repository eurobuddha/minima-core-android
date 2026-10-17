package com.eurobuddha.minimacore.integrated;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.history.HistoryDb;
import com.eurobuddha.history.HistoryEntry;
import com.eurobuddha.history.HistoryScreen;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

@RunWith(AndroidJUnit4.class)
public class HistoryLanguagesTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private HistoryEntry fixture;
    @Before public void prepare() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu", Build.HARDWARE);
        assertNull("Requires a stopped disposable node", MinimaService.minima);
        context.deleteDatabase("minima_history.db");
        fixture = HistoryEntry.fromJson(new JSONObject().put("txpowid", "0xDISPOSABLE")
                .put("direction", "received").put("incoming", true).put("amount", "12.340000")
                .put("block", 1234).put("timemilli", 1577880123000L)
                .put("tokenid", "0x1234").put("tokenName", "Literal 中文 Русский Українська")
                .put("counterparty", "MxDISPOSABLEADDRESS")
                .put("deltas", "{\"0x1234\":\"12.340000\"}"));
        try (HistoryDb db = new HistoryDb(context)) { assertTrue(db.insert(fixture)); }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppLock.disable(context); Language.select(context, "en");
        });
    }
    private static HistoryScreen screen(com.eurobuddha.history.MainActivity a) throws Exception {
        Field field = a.getClass().getDeclaredField("screen"); field.setAccessible(true);
        return (HistoryScreen) field.get(a);
    }
    private static void invoke(HistoryScreen screen, String name, Class<?> type, Object argument) throws Exception {
        Method method = HistoryScreen.class.getDeclaredMethod(name, type); method.setAccessible(true);
        method.invoke(screen, argument);
    }
    private void settle() {
        android.os.SystemClock.sleep(600);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void screenshot(String name) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "language-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(directory,name+".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        } finally { bitmap.recycle(); }
    }
    @Test public void historyDialogAndSearchSwitchLanguagesWithoutChangingExportedTransactions() throws Exception {
        try (ActivityScenario<com.eurobuddha.history.MainActivity> scenario = ActivityScenario.launch(com.eurobuddha.history.MainActivity.class)) {
            settle();
            AtomicReference<com.eurobuddha.history.MainActivity> original = new AtomicReference<>();
            AtomicReference<ExecutorService> executor = new AtomicReference<>();
            scenario.onActivity(a -> {
                try {
                    original.set(a);
                    EditText search = a.findViewById(com.eurobuddha.history.R.id.pm_history_search);
                    search.setText("Literal"); search.setSelection(4);
                    Field worker = HistoryScreen.class.getDeclaredField("io");worker.setAccessible(true);
                    executor.set((ExecutorService)worker.get(screen(a)));
                    invoke(screen(a),"showDetail",HistoryEntry.class,fixture);
                } catch(Exception e) { throw new AssertionError(e); }
            });
            for(String code : new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_history_transaction))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_history_direction)+":  "+Language.text(context,l10n_history_received)))
                        .inRoot(isDialog()).check(matches(isDisplayed()));
                screenshot("history-detail-"+code);
                File json = new File(context.getCacheDir(),"disposable-history-"+code+".json");
                File csv = new File(context.getCacheDir(),"disposable-history-"+code+".csv");
                scenario.onActivity(a -> {
                    try {
                        assertSame(original.get(),a);
                        EditText search=a.findViewById(com.eurobuddha.history.R.id.pm_history_search);
                        assertEquals("Literal",search.getText().toString());assertEquals(4,search.getSelectionStart());
                        assertEquals(Language.text(a,l10n_history_search),search.getHint().toString());
                        invoke(screen(a),"doExport",Uri.class,Uri.fromFile(json));
                        invoke(screen(a),"doExportCsv",Uri.class,Uri.fromFile(csv));
                    } catch(Exception e) { throw new AssertionError(e); }
                });
                executor.get().submit(() -> {}).get(10,TimeUnit.SECONDS);
                JSONObject record=new JSONObject(new String(Files.readAllBytes(json.toPath()),StandardCharsets.UTF_8)).getJSONArray("tx").getJSONObject(0);
                assertEquals("received",record.getString("direction"));
                assertEquals("12.340000",record.getString("amount"));
                assertEquals(fixture.tokenName,record.getString("tokenName"));
                assertEquals(fixture.txpowid,record.getString("txpowid"));
                String exported=new String(Files.readAllBytes(csv.toPath()),StandardCharsets.UTF_8);
                assertTrue(exported,exported.startsWith("txpowid,block,datetime,direction,amount,token,counterparty,deltas\n"));
                assertTrue(exported,exported.contains(",received,12.340000,"+fixture.tokenName+","));
                assertTrue(json.delete()); assertTrue(csv.delete());
            }
            pressBack();settle();
            for (String code : new String[]{"zh-Hans","ru","uk","en"}) {
                scenario.onActivity(a -> {
                    Language.select(a,code);
                    a.findViewById(com.eurobuddha.history.R.id.pm_history_menuBtn).performClick();
                });settle();
                onView(withText(Language.text(context,l10n_history_export_json))).check(matches(isDisplayed()));
                onView(withText(Language.text(context,l10n_history_import_history))).check(matches(isDisplayed()));
                pressBack();
            }
            assertNull(MinimaService.minima);
        }
    }
}
