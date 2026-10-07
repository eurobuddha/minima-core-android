package com.eurobuddha.minimacore.integrated;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.minima.utils.BIP39;
import org.minima.system.params.GeneralParams;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimacore.StartActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Run only on a fresh disposable emulator: -e pandamoniumDisposable true.
 * No transaction/signing commands, existing wallet imports or real device access.
 */
@RunWith(AndroidJUnit4.class)
public class PandamoniumIntegrationTest {
    private static final String SHELL = "pandamonium.navigation.shell";
    private void ui(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private Activity current() {
        AtomicReference<Activity> found = new AtomicReference<>();
        ui(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) found.set(a);
        });
        return found.get();
    }
    private Activity await(String name, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        long slowStartupRetry = SystemClock.elapsedRealtime() + 125_000;
        while (SystemClock.elapsedRealtime() < end) {
            Activity a = current();
            if (a != null && a.getClass().getName().equals(name)) return a;
            if (a instanceof com.eurobuddha.minimacore.launcher.StartServiceActivity
                    && SystemClock.elapsedRealtime() >= slowStartupRetry) {
                // Fresh 128x4 key generation can exceed the normal UI timeout on a busy host.
                confirmDialog("Startup is taking longer");
                slowStartupRetry = SystemClock.elapsedRealtime() + 125_000;
            }
            SystemClock.sleep(250);
        }
        fail("Destination did not open: " + name); return null;
    }
    private View find(View v, String value, boolean description) {
        if (description ? value.equals(v.getContentDescription()) : v instanceof TextView && value.contentEquals(((TextView)v).getText())) return v;
        if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) {
            View match = find(((ViewGroup)v).getChildAt(i), value, description);
            if (match != null) return match;
        }
        return null;
    }
    private JSONObject command(DirectNodeApi api, String command) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        api.Command(command, reply -> { result.set(reply); latch.countDown(); });
        assertTrue("Direct command completed", latch.await(30,TimeUnit.SECONDS)); return result.get();
    }
    private JSONObject file(DirectNodeApi api, String action, String path, String target, android.net.Uri source) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>();
        api.FileCommand(action,path,target,source,reply -> {result.set(reply);latch.countDown();});
        assertTrue("Direct file action completed",latch.await(30,TimeUnit.SECONDS));return result.get();
    }
    private void verifyDirectTransport(Context ctx, DirectNodeApi api) throws Exception {
        // Same admin runner, including structured multi-command replies without a text parser.
        JSONObject mode = command(api,"block");
        org.minima.utils.json.JSONObject nativeMode = org.minima.system.Main.getInstance().runSingleMinimaCMD("block");
        assertEquals(new JSONObject(nativeMode.toString()).toString(),mode.toString());
        // An offline fresh node has no blocks: preserve its real rejection exactly.
        JSONObject batch = command(api,"status;status");
        assertTrue(batch.toString(),batch.optBoolean("status")); assertEquals(2,batch.getJSONArray("response").length());
        assertFalse(command(api,"unknown_pandamonium_test_command").optBoolean("status"));
        CountDownLatch occupied=new CountDownLatch(1), release=new CountDownLatch(1), guardedReply=new CountDownLatch(1);
        AtomicBoolean authorized=new AtomicBoolean(true);
        AtomicReference<JSONObject> guardedResult=new AtomicReference<>();
        EmbeddedNodeTransport.commandExecutor().execute(() -> {
            occupied.countDown();
            try { release.await(15,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(occupied.await(10,TimeUnit.SECONDS));
            api.Command("unknown_pandamonium_guard_test",authorized::get,r->{guardedResult.set(r);guardedReply.countDown();});
            // Drain the main-thread submission while the shared execution queue is occupied.
            ui(() -> {});
            authorized.set(false);
        } finally { release.countDown(); }
        assertTrue(guardedReply.await(10,TimeUnit.SECONDS));
        assertTrue(guardedResult.get().has("transporterror"));
        assertFalse("Cancelled work never reaches the node's command rejection",guardedResult.get().has("status"));
        assertFalse("No internal pairing token",ctx.getSharedPreferences("minima_api_prefs",0).contains("myapp_uid"));
        android.content.pm.PackageInfo pkg=ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                android.content.pm.PackageManager.GET_SERVICES | android.content.pm.PackageManager.GET_RECEIVERS | android.content.pm.PackageManager.GET_PROVIDERS);
        for(android.content.pm.ServiceInfo service:pkg.services) {
            assertFalse("No IPC relay service",service.name.endsWith("NodeTransportService"));
            assertEquals("One process for services",ctx.getPackageName(),service.processName);
            if (service.name.equals("com.eurobuddha.casino.CasinoService"))
                assertTrue("Persistent casino watcher has specialUse",(service.getForegroundServiceType()
                        & android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0);
        }
        for(android.content.pm.ActivityInfo receiver:pkg.receivers)assertFalse(receiver.name.endsWith("MinimaNotifyReceiver"));
        for(android.content.pm.ProviderInfo provider:pkg.providers)assertFalse(provider.authority.endsWith(".filez.fileprovider"));
        java.io.File source=new java.io.File(ctx.getCacheDir(),"direct-transport-source.bin");
        byte[] bytes=new byte[384*1024];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
        java.nio.file.Files.write(source.toPath(),bytes);
        assertTrue(file(api,"mkdir","direct-file-test",null,null).optBoolean("status"));
        try {
            assertTrue(file(api,"put","direct-file-test/payload.bin",null,android.net.Uri.fromFile(source)).optBoolean("status"));
            JSONObject exported=file(api,"get","direct-file-test/payload.bin",null,null);
            assertTrue(exported.optBoolean("status")); assertEquals("file",android.net.Uri.parse(exported.getString("uri")).getScheme());
            try(java.io.InputStream input=ctx.getContentResolver().openInputStream(android.net.Uri.parse(exported.getString("uri")))) {
                assertArrayEquals(bytes,input.readAllBytes());
            }
            java.nio.file.Files.write(source.toPath(),new byte[]{3,2,1});
            assertTrue(file(api,"put","direct-file-test/payload.bin",null,android.net.Uri.fromFile(source)).optBoolean("status"));
            assertEquals(3,file(api,"get","direct-file-test/payload.bin",null,null).getLong("size"));
            assertTrue(file(api,"move","direct-file-test/payload.bin","direct-file-test/renamed.bin",null).optBoolean("status"));
            assertTrue(file(api,"list","direct-file-test",null,null).optBoolean("status"));
            assertFalse(file(api,"get","../outside",null,null).optBoolean("status"));
            assertFalse(file(api,"put","databases/forbidden.bin",null,android.net.Uri.fromFile(source)).optBoolean("status"));
            assertFalse(file(api,"delete","/",null,null).optBoolean("status"));
            assertFalse(file(api,"move","/","base-move",null).optBoolean("status"));
            java.io.File link=new java.io.File(ctx.getFilesDir(),"direct-file-test/link");
            java.nio.file.Files.createSymbolicLink(link.toPath(),source.toPath());
            assertFalse(file(api,"delete","direct-file-test",null,null).optBoolean("status"));
            assertTrue("Recursive deletion must preserve a symlink target outside base",source.isFile());
            java.nio.file.Files.delete(link.toPath());
        } finally {
            assertTrue(file(api,"delete","direct-file-test",null,null).optBoolean("status"));source.delete();
        }
        // The application log subscriber persists local events off the UI thread.
        com.eurobuddha.minimaapi.direct.DirectNodeEvents.publish(new JSONObject().put("event","MINIMALOG")
                .put("data",new JSONObject().put("message","pandamonium-direct-transport-test")));
        boolean persisted=false; long deadline=SystemClock.elapsedRealtime()+10_000;
        while(!persisted && SystemClock.elapsedRealtime()<deadline) {
            java.io.File db=ctx.getDatabasePath("minimaevents.db");
            if(db.exists())try(android.database.sqlite.SQLiteDatabase database=android.database.sqlite.SQLiteDatabase.openDatabase(db.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    android.database.Cursor cursor=database.rawQuery("SELECT count(*) FROM events WHERE data LIKE ?",new String[]{"%pandamonium-direct-transport-test%"})) {
                persisted=cursor.moveToFirst() && cursor.getInt(0)>0;
            }
            if(!persisted)SystemClock.sleep(100);
        }
        assertTrue("Local Terminal IDE log delivery",persisted);

        // FutureCash's second alert used to replace its untagged foreground notification (3101).
        android.app.NotificationManager notifications=ctx.getSystemService(android.app.NotificationManager.class);
        com.eurobuddha.futurecashnext.Notifier.ensureChannels(ctx);
        notifications.notify(3101,new android.app.Notification.Builder(ctx,com.eurobuddha.futurecashnext.Notifier.CH_FG)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("review sentinel").build());
        com.eurobuddha.futurecashnext.Notifier.alert(ctx,"review alert 1","fixture");
        com.eurobuddha.futurecashnext.Notifier.alert(ctx,"review alert 2","fixture");
        SystemClock.sleep(500);
        boolean sentinel=false; int alerts=0;
        for(android.service.notification.StatusBarNotification n:notifications.getActiveNotifications()) {
            if (n.getTag()==null && n.getId()==3101)
                sentinel="review sentinel".contentEquals(n.getNotification().extras.getCharSequence(android.app.Notification.EXTRA_TITLE,""));
            if ("pandamonium.futurecashnext".equals(n.getTag())) { alerts++; notifications.cancel(n.getTag(),n.getId()); }
        }
        notifications.cancel(3101);
        assertTrue("Alerts preserve foreground notification identity",sentinel); assertEquals(2,alerts);
    }
    @Test public void embeddedNodeAndEveryNativeDestination() throws Exception {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("pandamoniumDisposable"));
        assertTrue("Disposable emulator only", android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"));
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.eurobuddha.pandamonium", ctx.getPackageName());
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        assertFalse("Fresh application data required", prefs.getBoolean("SEED_SET", false));
        // Programmatic view clicks do not keep a headless emulator awake. Real dialogs
        // require window focus, so keep this disposable device unlocked through key generation.
        for (String fixtureCommand : new String[]{"svc power stayon true",
                "settings put system screen_off_timeout 1800000", "input keyevent 224", "wm dismiss-keyguard"}) {
            try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand(fixtureCommand);
                 java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                while (input.read() != -1) { }
            }
        }
        boolean classic = "true".equals(InstrumentationRegistry.getArguments().getString("classicMode"));
        ctx.startActivity(new Intent(ctx, com.eurobuddha.minimacore.launcher.LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity launcher = await("com.eurobuddha.minimacore.launcher.LauncherActivity", 15_000);
        ui(() -> launcher.findViewById(com.eurobuddha.minimacore.R.id.launcher_button_params).performClick());
        Activity settings = await("com.eurobuddha.minimacore.main.ParamsActivity", 10_000);
        ui(() -> {
            com.google.android.material.switchmaterial.SwitchMaterial toggle = settings.findViewById(
                    com.eurobuddha.minimacore.R.id.params_switch_classic);
            assertTrue(toggle.isShown()); assertFalse("Default mode", toggle.isChecked());
            assertEquals(View.GONE, settings.findViewById(com.eurobuddha.minimacore.R.id.params_button_save_restart).getVisibility());
            toggle.setChecked(classic);
            settings.findViewById(com.eurobuddha.minimacore.R.id.params_button_save).performClick();
        });
        await("com.eurobuddha.minimacore.launcher.LauncherActivity", 10_000);
        assertEquals(classic, prefs.getBoolean(com.eurobuddha.minimacore.main.StartupMode.PREF_CLASSIC_MODE, false));
        assertTrue(prefs.edit().putBoolean("SEED_SET", true)
                .putString("SEED", BIP39.convertWordListToString(BIP39.getNewWordList())).commit());
        // Test-fixture permissions avoid racing the first-run Android overlays with navigation.
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().grantRuntimePermission(ctx.getPackageName(), android.Manifest.permission.POST_NOTIFICATIONS);
        try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("dumpsys deviceidle whitelist +" + ctx.getPackageName());
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            while (in.read() != -1) { }
        }
        ctx.startActivity(new Intent(ctx, StartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity core = await(PandamoniumDestinations.CLASSES[0], 600_000);
        // Let Android finish/dismiss the first-launch storage permission sheet before API checks.
        SystemClock.sleep(1500);
        if (current() == null) {
            try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand("input keyevent 4");
                 java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                while (in.read() != -1) { }
            }
            core = await(PandamoniumDestinations.CLASSES[0], 10_000);
        }
        final Activity retainedCore = core;
        assertMode(!classic);
        java.util.ArrayList<org.minima.database.wallet.KeyRow> initialKeys =
                org.minima.database.MinimaDB.getDB().getWallet().getAllKeys();
        assertFalse(initialKeys.isEmpty());
        assertEquals(classic ? 64 : 128, initialKeys.get(0).getSize());
        assertEquals(classic ? 3 : 4, initialKeys.get(0).getDepth());

        CountDownLatch registered = new CountDownLatch(1), answered = new CountDownLatch(1);
        AtomicReference<JSONObject> registration = new AtomicReference<>(), status = new AtomicReference<>();
        AtomicReference<DirectNodeApi> client = new AtomicReference<>();
        ui(() -> client.set(new DirectNodeApi(ctx, reply -> { registration.set(reply); registered.countDown(); })));
        assertTrue("Internal registration", registered.await(20, TimeUnit.SECONDS));
        assertTrue(registration.get().toString(), registration.get().optBoolean("enabled"));
        ui(() -> client.get().Command("status", reply -> { status.set(reply); answered.countDown(); }));
        assertTrue("Internal read command", answered.await(20, TimeUnit.SECONDS));
        assertTrue(status.get().toString(), status.get().optBoolean("status"));
        verifyDirectTransport(ctx, client.get());
        ui(() -> client.get().onDestroy());
        if ("true".equals(InstrumentationRegistry.getArguments().getString("externalCompanion"))) verifyExternalCompanion(ctx);

        // A non-zero disposable counter proves mode changes do not reset signing history.
        org.minima.database.wallet.Wallet wallet = org.minima.database.MinimaDB.getDB().getWallet();
        wallet.updateAllKeyUses(7000);
        verifyModeSwitch(ctx, !classic);
        verifyModeSwitch(ctx, classic);

        java.util.List<Activity> retainedScreens = new java.util.ArrayList<>();
        ui(() -> ((com.eurobuddha.minimacore.MinimaApplication)retainedCore.getApplication()).setScreenshotsAllowed(false));
        for (int i=1; i<=PandamoniumDestinations.CLASSES.length; i++) {
            int destination = i % PandamoniumDestinations.CLASSES.length;
            // A first-launch Android permission sheet can temporarily pause the app.
            Activity active = current();
            if (active == null) {
                try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation()
                        .getUiAutomation().executeShellCommand("input keyevent 4");
                     java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                    while (in.read() != -1) { }
                }
                active = await(PandamoniumDestinations.CLASSES[(i-1) % PandamoniumDestinations.CLASSES.length], 10_000);
            }
            final Activity screen = active;
            ui(() -> {
                DrawerLayout drawer = screen.findViewById(android.R.id.content).findViewWithTag(SHELL);
                assertNotNull("Shared menu on " + screen.getClass().getName(), drawer);
                View hamburger = find(drawer, "Open Minima Core menu", true);
                assertNotNull(hamburger);
                int[] position = new int[2]; hamburger.getLocationOnScreen(position);
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(drawer);
                assertTrue("Hamburger clears status bar", position[1] >= insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top);
                assertTrue("Hamburger is on the left", position[0] < drawer.getWidth() / 2);
                hamburger.performClick();
            });
            long drawerDeadline = SystemClock.elapsedRealtime() + 8_000;
            AtomicBoolean drawerOpen = new AtomicBoolean();
            while (!drawerOpen.get() && SystemClock.elapsedRealtime() < drawerDeadline) {
                ui(() -> {
                    DrawerLayout drawer = screen.findViewById(android.R.id.content).findViewWithTag(SHELL);
                    drawerOpen.set(drawer.isDrawerOpen(Gravity.LEFT));
                });
                SystemClock.sleep(100);
            }
            ui(() -> {
                DrawerLayout drawer = screen.findViewById(android.R.id.content).findViewWithTag(SHELL);
                assertTrue("Left-side drawer on " + screen.getClass().getName(), drawer.isDrawerOpen(Gravity.LEFT));
                View entry = find(drawer, PandamoniumDestinations.NAMES[destination], true);
                assertNotNull(entry); entry.performClick();
            });
            Activity opened = await(PandamoniumDestinations.CLASSES[destination], 20_000);
            SystemClock.sleep(750);
            ui(() -> assertNotNull(opened.findViewById(android.R.id.content).findViewWithTag(SHELL)));
            retainedScreens.add(opened);
            ui(() -> assertTrue("Screenshots initially blocked: " + opened.getClass().getName(),
                    (opened.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0));
            if (destination == 0) assertSame("Core screen retained", core, opened);
        }
        ui(() -> {
            com.eurobuddha.minimacore.main.MainActivity home = (com.eurobuddha.minimacore.main.MainActivity)retainedCore;
            android.view.MenuItem toggle = new android.widget.PopupMenu(home, home.findViewById(android.R.id.content)).getMenu().add("Allow Screenshots");
            home.toggleScreenshots(toggle);
            for (Activity screen : retainedScreens) if (!screen.isDestroyed()) assertEquals("Allow updates retained window: " + screen.getClass().getName(),
                    0, screen.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE);
            home.toggleScreenshots(toggle);
            for (Activity screen : retainedScreens) if (!screen.isDestroyed()) assertTrue("Block updates retained window: " + screen.getClass().getName(),
                    (screen.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0);
            home.toggleScreenshots(toggle);
            DrawerLayout drawer = retainedCore.findViewById(android.R.id.content).findViewWithTag(SHELL);
            drawer.openDrawer(Gravity.LEFT, false);
            ((androidx.appcompat.app.AppCompatActivity)retainedCore).getOnBackPressedDispatcher().onBackPressed();
        });
        SystemClock.sleep(500);
        ui(() -> {
            DrawerLayout drawer = retainedCore.findViewById(android.R.id.content).findViewWithTag(SHELL);
            assertFalse("Back closes the menu", drawer.isDrawerOpen(Gravity.LEFT));
        });
        // Android can recreate a stopped Activity after a configuration change. Check the actual
        // resumed window too, rather than holding a stale destroyed instance as proof of capture policy.
        for (int i = 1; i <= PandamoniumDestinations.CLASSES.length; i++) {
            String name = PandamoniumDestinations.CLASSES[i % PandamoniumDestinations.CLASSES.length];
            Activity origin = current();
            ui(() -> origin.startActivity(new Intent().setClassName(ctx.getPackageName(), name)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
            Activity screen = await(name, 20_000);
            ui(() -> assertEquals("Allow screenshots on resumed screen: " + name, 0,
                    screen.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE));
        }
    }

    private void confirmDialog(String title) {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.withDecorView(
                        androidx.test.espresso.matcher.ViewMatchers.hasDescendant(
                                androidx.test.espresso.matcher.ViewMatchers.withText(title))))
                .perform(androidx.test.espresso.action.ViewActions.click());
    }

    private void verifyExternalCompanion(Context ctx) throws Exception {
        // The test above has already required a fresh, disposable emulator wallet.
        com.eurobuddha.minimacore.receiver.ReceiverDB db = new com.eurobuddha.minimacore.receiver.ReceiverDB(ctx);
        try {
          for (String companion : new String[]{"com.eurobuddha.mail", "com.eurobuddha.pandadex"}) {
            externalReadTest(companion, false, false);
            org.minima.utils.json.JSONObject registered = null;
            for (Object row : db.selectAllApps()) {
                org.minima.utils.json.JSONObject app = (org.minima.utils.json.JSONObject)row;
                if (companion.equals(app.get("package"))) registered = app;
            }
            assertNotNull("Standalone Mail appears in core registrations", registered);
            String id = (String) registered.get("packageid");
            db.setEnabled(companion, id, true);
            externalReadTest(companion, true, false);
            db.setAdmin(companion, id, true);
            externalReadTest(companion, true, true);
            db.setEnabled(companion, id, false);
            db.setAdmin(companion, id, false);
            externalReadTest(companion, false, false);
          }
        } finally { db.close(); }
    }

    private void externalReadTest(String companion, boolean enabled, boolean admin) throws Exception {
        String command = "am instrument -w -r -e class com.eurobuddha.review.ExternalCompatibilityTest"
                + " -e companionPackage " + companion + " -e pandamoniumDisposable true -e enabled " + enabled + " -e admin " + admin
                + " " + companion + ".test/androidx.test.runner.AndroidJUnitRunner";
        try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            String result = out.toString("UTF-8");
            assertTrue("External permissions enabled=" + enabled + " admin=" + admin + ": " + result, result.contains("OK (1 test)"));
        }
    }

    private void assertMode(boolean block) {
        assertEquals(block, GeneralParams.USE_BLOCK_AS_KEYUSES);
        assertEquals(block, GeneralParams.USE_SQL_COINDB);
        assertEquals(block, GeneralParams.USE_SQL_TXBLOCKDB);
    }

    private void verifyModeSwitch(Context ctx, boolean classic) throws Exception {
        org.minima.system.Main previous = org.minima.system.Main.getInstance();
        boolean wasBlock = GeneralParams.USE_BLOCK_AS_KEYUSES;
        java.util.Map<String, String> keys = new java.util.HashMap<>();
        for (org.minima.database.wallet.KeyRow row : org.minima.database.MinimaDB.getDB().getWallet().getAllKeys())
            keys.put(row.getPublicKey(), row.getSize() + ":" + row.getDepth() + ":" + row.getUses());
        Activity origin = await(PandamoniumDestinations.CLASSES[0], 15_000);
        ui(() -> origin.startActivity(new Intent(origin, com.eurobuddha.minimacore.main.ParamsActivity.class)));
        Activity settings = await("com.eurobuddha.minimacore.main.ParamsActivity", 10_000);
        AtomicBoolean focused = new AtomicBoolean();
        long focusDeadline = SystemClock.elapsedRealtime() + 15_000;
        while (!focused.get() && SystemClock.elapsedRealtime() < focusDeadline) {
            ui(() -> focused.set(settings.hasWindowFocus()));
            SystemClock.sleep(100);
        }
        assertTrue("Settings has focus before opening confirmation", focused.get());
        ui(() -> {
            com.google.android.material.switchmaterial.SwitchMaterial toggle = settings.findViewById(
                    com.eurobuddha.minimacore.R.id.params_switch_classic);
            assertEquals(!classic, toggle.isChecked());
            toggle.setChecked(classic);
            settings.findViewById(com.eurobuddha.minimacore.R.id.params_button_save_restart).performClick();
        });
        assertEquals(classic, ctx.getSharedPreferences("main_prefs", 0)
                .getBoolean(com.eurobuddha.minimacore.main.StartupMode.PREF_CLASSIC_MODE, false));
        assertMode(wasBlock); // Saving a preference does not mutate the live node.
        confirmDialog("Restart node?");
        long deadline = SystemClock.elapsedRealtime() + 180_000;
        boolean restarted = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            org.minima.system.Main now = org.minima.system.Main.getInstance();
            if (now != null && now != previous && now.isStartUpComplete()
                    && !com.eurobuddha.minimacore.service.MinimaService.haveStartedShutdown()) {
                restarted = true; break;
            }
            SystemClock.sleep(250);
        }
        assertTrue("Clean node restart completed", restarted);
        Activity core = await(PandamoniumDestinations.CLASSES[0], 15_000);
        assertMode(!classic);
        for (org.minima.database.wallet.KeyRow row : org.minima.database.MinimaDB.getDB().getWallet().getAllKeys()) {
            String before = keys.remove(row.getPublicKey());
            if (before != null) assertEquals("Stored key shape and counter retained", before,
                    row.getSize() + ":" + row.getDepth() + ":" + row.getUses());
        }
        assertTrue("All original keys retained", keys.isEmpty());
        ui(() -> {
            DrawerLayout drawer = core.findViewById(android.R.id.content).findViewWithTag(SHELL);
            find(drawer, "Open Minima Core menu", true).performClick();
            assertNotNull(find(drawer, classic ? "Classic mode" : "Block key uses · low-RAM node", false));
            drawer.closeDrawer(Gravity.LEFT, false);
        });
    }
}
