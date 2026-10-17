package com.eurobuddha.minimacore.integrated;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/** Shared drawer and retained-screen regression on a disposable emulator with no running node. */
@RunWith(AndroidJUnit4.class)
public class IntegratedLanguagesTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void prepare() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu", Build.HARDWARE);
        assertNull("This test requires a stopped disposable node", MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppLock.disable(context);
            Language.select(context, "en");
        });
        settle();
    }
    @After public void reset() {
        if (!"ranchu".equals(Build.HARDWARE) || !"true".equals(
                InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"))) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Language.select(context, "en"));
    }
    private void settle() {
        android.os.SystemClock.sleep(700);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void screenshot(String name) throws Exception {
        java.io.File directory = new java.io.File(context.getExternalFilesDir(null), "language-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }
    @Test public void everyIntegratedActivityRetainsViewsOnLanguageChanges() throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_ACTIVITIES);
        java.util.Set<String> covered = new java.util.HashSet<>();
        String[] modules = {"ethwallet", "atomix", "pandapools", "futurecashnext", "pandadex", "casino",
                "blockexplorer", "filez", "history", "vestr", "entropy", "terminalide", "mail", "utxo", "merchshop", "merchstudio", "merchinbox", "nftwallet", "wallet"};
        for (ActivityInfo activity : info.activities) for (String module : modules) {
            if (!activity.name.startsWith("com.eurobuddha." + module + ".")) continue;
            covered.add(module);
            int required = ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION | ActivityInfo.CONFIG_SCREEN_LAYOUT;
            assertEquals(activity.name, required, activity.configChanges & required);
        }
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(modules)), covered);
    }
    @Test public void drawerSelectorPreservesExplorerDraftAndActivityAcrossAllLanguages() throws Exception {
        try (ActivityScenario<com.eurobuddha.blockexplorer.MainActivity> scenario =
                ActivityScenario.launch(com.eurobuddha.blockexplorer.MainActivity.class)) {
            AtomicReference<com.eurobuddha.blockexplorer.MainActivity> original = new AtomicReference<>();
            scenario.onActivity(a -> {
                original.set(a);
                EditText search = a.findViewById(com.eurobuddha.blockexplorer.R.id.pm_blockexplorer_search);
                search.setText("MxABC 中文 Русский Українська");
                search.setSelection(5);
            });
            String[] codes = {"zh-Hans", "ru", "uk", "en"};
            String[] names = {"简体中文", "Русский", "Українська", "English"};
            for (int index = 0; index < codes.length; index++) {
                scenario.onActivity(a -> {
                    DrawerLayout drawer = a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell");
                    drawer.openDrawer(Gravity.LEFT, false);
                    TextView selector = drawer.findViewWithTag("pandamonium.navigation.language");
                    assertEquals(Language.text(a, l10n_language_title), selector.getText().toString());
                    selector.performClick();
                });
                onView(withText(names[index])).inRoot(isDialog()).perform(click());
                settle();
                assertEquals(codes[index], Language.code());
                scenario.onActivity(a -> {
                    assertSame("Language change recreated the embedded screen", original.get(), a);
                    EditText search = a.findViewById(com.eurobuddha.blockexplorer.R.id.pm_blockexplorer_search);
                    assertEquals("MxABC 中文 Русский Українська", search.getText().toString());
                    assertEquals(5, search.getSelectionStart());
                    DrawerLayout drawer = a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell");
                    drawer.openDrawer(Gravity.LEFT, false);
                    assertEquals(Language.text(a, l10n_drawer_apps), drawer.getDrawerTitle(Gravity.LEFT));
                    TextView selector = drawer.findViewWithTag("pandamonium.navigation.language");
                    assertEquals(Language.text(a, l10n_language_title), selector.getText().toString());
                });
                settle();
                screenshot("integrated-drawer-" + codes[index]);
                scenario.onActivity(a -> {
                    DrawerLayout drawer = a.findViewById(android.R.id.content).findViewWithTag("pandamonium.navigation.shell");
                    drawer.closeDrawer(Gravity.LEFT, false);
                });
            }
            assertNull("Selector must not start a node", MinimaService.minima);
        }
    }
    @Test public void utxoSelectionAndSendDraftSurviveAllLanguages() throws Exception {
        try (ActivityScenario<com.eurobuddha.utxo.MainActivity> scenario =
                ActivityScenario.launch(com.eurobuddha.utxo.MainActivity.class)) {
            settle();
            AtomicReference<com.eurobuddha.utxo.MainActivity> original = new AtomicReference<>();
            final String recipient = "MxDISPOSABLE中文РусскийУкраїнська";
            scenario.onActivity(a -> {
                original.set(a);
                assertEquals("pandamonium_utxo_history.db", a.history().getDatabaseName());
                com.eurobuddha.utxo.Coin coin = new com.eurobuddha.utxo.Coin();
                coin.coinid = "0xdisposable";
                coin.address = "0xdisposable";
                coin.miniaddress = "MxDISPOSABLE";
                coin.tokenid = "0x00";
                coin.amount = "50";
                coin.tokenName = "Minima";
                coin.sendable = true;
                a.coins().add(coin);
                a.toggleCoin(coin);
                a.goToTab(com.eurobuddha.utxo.MainActivity.TAB_SEND);
                EditText address = a.findViewById(com.eurobuddha.utxo.R.id.pm_utxo_sendAddress);
                address.setText(recipient);
                address.setSelection(5);
                ((EditText) a.findViewById(com.eurobuddha.utxo.R.id.pm_utxo_sendAmount)).setText("12.340000");
                ((EditText) a.findViewById(com.eurobuddha.utxo.R.id.pm_utxo_sendChange)).setText("MxCHANGE");
                ((EditText) a.findViewById(com.eurobuddha.utxo.R.id.pm_utxo_sendBurn)).setText("0.001");
            });
            for (String code : new String[]{"zh-Hans", "ru", "uk", "en"}) {
                scenario.onActivity(a -> Language.select(a, code));
                settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(), a);
                    assertEquals(com.eurobuddha.utxo.MainActivity.TAB_SEND, a.currentTab());
                    assertTrue(a.isSelected("0xdisposable"));
                    EditText address = a.findViewById(com.eurobuddha.utxo.R.id.pm_utxo_sendAddress);
                    assertEquals(recipient, address.getText().toString());
                    assertEquals(5, address.getSelectionStart());
                    assertEquals("12.340000", ((EditText) a.findViewById(
                            com.eurobuddha.utxo.R.id.pm_utxo_sendAmount)).getText().toString());
                    assertEquals("MxCHANGE", ((EditText) a.findViewById(
                            com.eurobuddha.utxo.R.id.pm_utxo_sendChange)).getText().toString());
                    assertEquals("0.001", ((EditText) a.findViewById(
                            com.eurobuddha.utxo.R.id.pm_utxo_sendBurn)).getText().toString());
                    assertNotNull(a.findViewById(android.R.id.content)
                            .findViewWithTag("pandamonium.navigation.shell"));
                });
            }
            assertNull("Draft checks must not start a node", MinimaService.minima);
        }
    }

    @Test public void nftSelectionAndSendDraftSurviveAllLanguages() throws Exception {
        try (ActivityScenario<com.eurobuddha.nftwallet.MainActivity> scenario =
                ActivityScenario.launch(com.eurobuddha.nftwallet.MainActivity.class)) {
            settle();
            AtomicReference<com.eurobuddha.nftwallet.MainActivity> original = new AtomicReference<>();
            final String recipient = "MxDISPOSABLE中文РусскийУкраїнська";
            scenario.onActivity(a -> {
                original.set(a);
                assertEquals("pandamonium_nft_history.db", a.history().getDatabaseName());
                com.eurobuddha.nftwallet.Coin coin = new com.eurobuddha.nftwallet.Coin();
                coin.coinid = "0xdisposable";
                coin.address = "0xdisposable";
                coin.miniaddress = "MxDISPOSABLE";
                coin.tokenid = "0x00";
                coin.amount = "50";
                coin.tokenName = "Minima";
                coin.sendable = true;
                a.coins().add(coin);
                a.toggleCoin(coin);
                a.goToTab(com.eurobuddha.nftwallet.MainActivity.TAB_SEND);
                EditText address = a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendAddress);
                address.setText(recipient);
                address.setSelection(5);
                ((EditText) a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendAmount)).setText("12.340000");
                ((EditText) a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendChange)).setText("MxCHANGE");
                ((EditText) a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendBurn)).setText("0.001");
            });
            for (String code : new String[]{"zh-Hans", "ru", "uk", "en"}) {
                scenario.onActivity(a -> Language.select(a, code));
                settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(), a);
                    assertEquals(com.eurobuddha.nftwallet.MainActivity.TAB_SEND, a.currentTab());
                    assertTrue(a.isSelected("0xdisposable"));
                    EditText address = a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendAddress);
                    assertEquals(recipient, address.getText().toString());
                    assertEquals(5, address.getSelectionStart());
                    assertEquals("12.340000", ((EditText) a.findViewById(
                            com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendAmount)).getText().toString());
                    assertEquals("MxCHANGE", ((EditText) a.findViewById(
                            com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendChange)).getText().toString());
                    assertEquals("0.001", ((EditText) a.findViewById(
                            com.eurobuddha.nftwallet.R.id.pm_nftwallet_sendBurn)).getText().toString());
                    assertNotNull(a.findViewById(android.R.id.content)
                            .findViewWithTag("pandamonium.navigation.shell"));
                });
            }
            assertNull("Draft checks must not start a node", MinimaService.minima);
        }
    }

    @Test public void nftMintFormAndCursorSurviveAllLanguages() throws Exception {
        try (ActivityScenario<com.eurobuddha.nftwallet.MainActivity> scenario =
                ActivityScenario.launch(com.eurobuddha.nftwallet.MainActivity.class)) {
            settle();
            AtomicReference<com.eurobuddha.nftwallet.MainActivity> original = new AtomicReference<>();
            AtomicReference<EditText> name = new AtomicReference<>();
            AtomicReference<EditText> amount = new AtomicReference<>();
            scenario.onActivity(a -> {
                original.set(a);
                a.goToTab(com.eurobuddha.nftwallet.MainActivity.TAB_MINT);
                android.widget.LinearLayout hub = a.findViewById(com.eurobuddha.nftwallet.R.id.pm_nftwallet_mintRoot);
                assertTrue(hub.getChildAt(0).performClick()); // existing Token creation card
                try {
                    java.lang.reflect.Field viewsField = a.getClass().getDeclaredField("views");
                    viewsField.setAccessible(true);
                    Object mint = ((Object[]) viewsField.get(a))[com.eurobuddha.nftwallet.MainActivity.TAB_MINT];
                    java.lang.reflect.Field nameField = mint.getClass().getDeclaredField("tName");
                    java.lang.reflect.Field amountField = mint.getClass().getDeclaredField("tAmount");
                    nameField.setAccessible(true); amountField.setAccessible(true);
                    name.set((EditText) nameField.get(mint)); amount.set((EditText) amountField.get(mint));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                name.get().setText("Draft 中文 Русский Українська");
                name.get().setSelection(5);
                amount.get().setText("12340000");
            });
            for (String code : new String[]{"zh-Hans", "ru", "uk", "en"}) {
                scenario.onActivity(a -> Language.select(a, code));
                settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(), a);
                    assertEquals(com.eurobuddha.nftwallet.MainActivity.TAB_MINT, a.currentTab());
                    assertTrue(name.get().isAttachedToWindow());
                    assertEquals("Draft 中文 Русский Українська", name.get().getText().toString());
                    assertEquals(5, name.get().getSelectionStart());
                    assertEquals("12340000", amount.get().getText().toString());
                    assertEquals(0, com.eurobuddha.nftwallet.LocalStore.load(a).length());
                });
            }
        }
        assertNull("Draft checks must not start a node", MinimaService.minima);
    }

    @Test public void singleMallEntryAndTranslatedHubRemainStableAcrossLanguages() throws Exception {
        int index = PandamoniumDestinations.indexFor(MallActivity.class.getName());
        assertTrue(index > 0);
        for (String name : new String[]{"merchshop", "merchstudio", "merchinbox"})
            assertEquals(index, PandamoniumDestinations.indexFor("com.eurobuddha." + name + ".MainActivity"));
        assertEquals(1, java.util.Collections.frequency(java.util.Arrays.asList(PandamoniumDestinations.NAMES), "miniMall"));
        try (ActivityScenario<MallActivity> scenario = ActivityScenario.launch(MallActivity.class)) {
            AtomicReference<MallActivity> original = new AtomicReference<>();
            scenario.onActivity(original::set);
            for (String code : new String[]{"en", "zh-Hans", "ru", "uk"}) {
                scenario.onActivity(a -> Language.select(a, code));
                settle();
                scenario.onActivity(a -> {
                    assertSame(original.get(), a);
                    View root = a.findViewById(android.R.id.content);
                    assertNotNull(root.findViewWithTag("pandamonium.navigation.shell"));
                    TextView intro = root.findViewWithTag("pandamonium.mall.intro");
                    assertEquals(Language.text(a, l10n_mall_intro), intro.getText().toString());
                    String[] names = {"shop", "studio", "inbox"};
                    int[] messages = {l10n_mall_shop_description, l10n_mall_studio_description, l10n_mall_inbox_description};
                    for (int i = 0; i < names.length; i++) {
                        View card = root.findViewWithTag("pandamonium.mall." + names[i]);
                        assertNotNull(card);
                        assertTrue(card.getContentDescription().toString(),
                                card.getContentDescription().toString().contains(Language.text(a, messages[i])));
                    }
                });
                screenshot("mall-hub-" + code);
            }
        }
        assertNull("Hub must not start a node", MinimaService.minima);
    }

    @Test public void allThreeMallAppsOpenInsideTheSharedDrawerWithoutANode() throws Exception {
        Class<?>[] apps = {com.eurobuddha.merchshop.MainActivity.class,
                com.eurobuddha.merchstudio.MainActivity.class, com.eurobuddha.merchinbox.MainActivity.class};
        for (Class<?> app : apps) {
            try (ActivityScenario<android.app.Activity> scenario = ActivityScenario.launch(
                    new android.content.Intent(context, app))) {
                settle();
                scenario.onActivity(a -> assertNotNull(app.getName(), a.findViewById(android.R.id.content)
                        .findViewWithTag("pandamonium.navigation.shell")));
            }
        }
        assertNull("Opening a Mall app must not start a node", MinimaService.minima);
    }

    @Test public void mallDatabasesHaveIndependentBookmarksAndOrderStorage() {
        try (com.eurobuddha.merchshop.integratedcomms.MerchDb shop = new com.eurobuddha.merchshop.integratedcomms.MerchDb(context);
             com.eurobuddha.merchstudio.integratedcomms.MerchDb studio = new com.eurobuddha.merchstudio.integratedcomms.MerchDb(context);
             com.eurobuddha.merchinbox.integratedcomms.MerchDb inbox = new com.eurobuddha.merchinbox.integratedcomms.MerchDb(context)) {
            assertEquals("pandamonium_merchshop.db", shop.getDatabaseName());
            assertEquals("pandamonium_merchstudio.db", studio.getDatabaseName());
            assertEquals("pandamonium_merchinbox.db", inbox.getDatabaseName());
            String marker = "disposable_language_test";
            shop.setMeta(marker, "buyer");
            studio.setMeta(marker, "studio");
            inbox.setMeta(marker, "seller");
            assertEquals("buyer", shop.getMeta(marker, ""));
            assertEquals("studio", studio.getMeta(marker, ""));
            assertEquals("seller", inbox.getMeta(marker, ""));
        }
    }
}
