package com.eurobuddha.atomix;

import android.content.SharedPreferences;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.Order;
import com.eurobuddha.atomix.swap.SwapDb;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public amounts and stopped workers only. Never confirms a valid swap. */
public class AtomixSwapInputsTest extends AtomixMarketLanguagesTest {
    private static final String[] LANGS = {"en", "zh-Hans", "ru", "uk"};
    private static final String HUGE = "9".repeat(340);
    private static Object quote(MainActivity a, String method, String value, double price) {
        return call(a, method, new Class<?>[]{String.class, double.class}, value, price);
    }
    @Test public void quotesRejectZeroLegsOverflowAndInvalidSyntaxAndMatchTradeGrain() throws Exception {
        try (ActivityScenario<MainActivity> run = ActivityScenario.launch(MainActivity.class)) {
            settle(); run.onActivity(a -> {
                freeze(a); publicWallet(a);
                for (String method : new String[]{"computeUsdt", "computeMinima", "ceilUsdt"}) {
                    for (String bad : new String[]{null, "", ".", "0", "-1", "1e3", "NaN", "Infinity", HUGE, "0.0000009"})
                        assertNull(method + ": " + bad, quote(a, method, bad, 1));
                    for (double bad : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
                        assertNull(method, quote(a, method, "1", bad));
                    assertEquals("12.34", quote(a, method, "00012.34000", 1));
                }
                assertEquals("0.246912", quote(a, "computeUsdt", "0.1234569", 2));
                assertEquals("0.123456", quote(a, "computeMinima", "0.2469139", 2));
                assertNull(quote(a, "computeUsdt", "0.000001", 0.1));
                assertNull(quote(a, "computeMinima", "0.000001", 2));
                assertEquals("0.000001", quote(a, "ceilUsdt", "0.000001", 0.1));
                String max = java.math.BigDecimal.valueOf(Double.MAX_VALUE).toPlainString();
                assertNull(quote(a, "computeUsdt", max, 2));
                assertNull(quote(a, "computeMinima", max, 0.1));
                assertNull(quote(a, "ceilUsdt", max, 2));
                assertTrue(((SwapDb)get(a, "db")).allSwaps().isEmpty());
            });
        }
    }
    @Test public void invalidSlippageRetainsDraftSelectionPreferencesAndTranslatedError() throws Exception {
        try (ActivityScenario<MainActivity> run = ActivityScenario.launch(MainActivity.class)) {
            settle(); AtomicReference<AlertDialog> kept = new AtomicReference<>();
            AtomicReference<EditText> input = new AtomicReference<>();
            AtomicReference<Map<String, ?>> before = new AtomicReference<>();
            run.onActivity(a -> {
                freeze(a); publicWallet(a); before.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));
                call(a,"customSlippageDialog"); kept.set(dialog(a)); input.set(inputs(kept.get()).get(0)); stopTicks(a);
            });
            for (String bad : new String[]{"", ".", HUGE, "0." + "0".repeat(340) + "1"}) {
                run.onActivity(a -> { input.get().setText(bad); input.get().setSelection(Math.min(3,bad.length())); kept.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); });
                for (String lang : LANGS) {
                    run.onActivity(a -> Language.select(a,lang)); settle();
                    run.onActivity(a -> {
                        assertSame(kept.get(),dialog(a)); assertTrue(kept.get().isShowing());
                        assertEquals(bad,input.get().getText().toString()); assertEquals(Math.min(3,bad.length()),input.get().getSelectionStart());
                        assertEquals(before.get(),((SharedPreferences)get(a,"prefs")).getAll());
                        exact(kept.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_invalid_number));
                        stopTicks(a);
                    });
                }
            }
            run.onActivity(a -> { input.get().setText("."); kept.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); });
            screenshot("atomix-swap-input-slippage");
            run.onActivity(a -> { input.get().setText("3.5"); kept.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertFalse(kept.get().isShowing()); assertEquals(3.5f,((SharedPreferences)get(a,"prefs")).getFloat("buy_slippage_pct",0),0); stopTicks(a); });
        }
    }
    @Test public void invalidMakerAmountAndCapsKeepDialogAndDraftWithoutStarting() throws Exception {
        try (ActivityScenario<MainActivity> run = ActivityScenario.launch(MainActivity.class)) {
            settle(); AtomicReference<AlertDialog> kept = new AtomicReference<>(); AtomicReference<EditText> input = new AtomicReference<>();
            run.onActivity(a -> { freeze(a); publicWallet(a); });
            for (boolean sell : new boolean[]{true,false}) {
                run.onActivity(a -> {
                    Order maker = maker(); maker.pairs.get("USDT").min=1;
                    call(a,"takeOrderDialog",new Class<?>[]{Order.class,String.class,boolean.class,double.class,double.class},maker,"USDT",sell,2d,100d);
                    kept.set(dialog(a)); input.set(inputs(kept.get()).get(0)); stopTicks(a);
                });
                for (String bad : new String[]{".", HUGE, "0.0000009", "0", "101", "0.5"}) {
                    run.onActivity(a -> { input.get().setText(bad); input.get().setSelection(1); kept.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); stopTicks(a); });
                    for (String lang : LANGS) {
                        run.onActivity(a -> Language.select(a,lang)); settle();
                        run.onActivity(a -> { assertSame(kept.get(),dialog(a)); assertTrue(kept.get().isShowing()); assertEquals(bad,input.get().getText().toString()); assertEquals(1,input.get().getSelectionStart()); assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty()); stopTicks(a); });
                    }
                }
                run.onActivity(a -> { input.get().setText("0.0000009"); kept.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); exact(kept.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_amount_invalid,TradingContext.active().coinLabel)); });
                screenshot("atomix-swap-input-take-"+(sell?"sell":"buy"));
                run.onActivity(a -> { kept.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); stopTicks(a); });
            }
        }
    }
    @Test public void invalidReviewsAndSweepTargetsDoNotCreatePlansOrEraseDrafts() throws Exception {
        try (ActivityScenario<MainActivity> run = ActivityScenario.launch(MainActivity.class)) {
            settle(); run.onActivity(a -> {
                freeze(a); publicWallet(a); Order maker = maker(); ((Map<String,Order>)get(a,"orderBook")).put(maker.signerPk,maker);
                set(a,"swapAmount","00012.34000");
                for (boolean sell : new boolean[]{true,false}) {
                    for (String bad : new String[]{".", HUGE, "0.0000009", "NaN", "1e3"}) {
                        call(a,"reviewSwap",new Class<?>[]{Order.class,boolean.class,String.class,double.class,double.class},maker,sell,bad,2d,100d);
                        assertFalse((Boolean)get(a,"modalOpen"));
                        Object plan=call(a,"buildSweepPlan",new Class<?>[]{boolean.class,String.class,double.class},sell,bad,.1d);
                        assertTrue(((List<?>)get(plan,"legs")).isEmpty()); assertEquals(0d,(Double)get(plan,"totalUsdt"),0);
                    }
                    for (double bad : new double[]{Double.NaN,Double.POSITIVE_INFINITY,-.1,.51}) {
                        Object plan=call(a,"buildSweepPlan",new Class<?>[]{boolean.class,String.class,double.class},sell,"12",bad);
                        assertTrue(((List<?>)get(plan,"legs")).isEmpty());
                    }
                }
                assertEquals("00012.34000",get(a,"swapAmount")); assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());
                call(a,"reviewSwap",new Class<?>[]{Order.class,boolean.class,String.class,double.class,double.class},maker,false,"0.2469139",2d,100d);
                AlertDialog review=dialog(a);
                exact(review.getWindow().getDecorView(),Language.msg(l10n_atomix_swap_buy_body,"0.123456",TradingContext.active().coinLabel,"0.246913","2",TradingContext.active().coinLabel,maker.signerPk));
                review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); stopTicks(a);
            });
        }
    }
    @Test public void damagedSlippagePreferenceUsesDefaultAndNonfiniteSetterCannotSave() throws Exception {
        try (ActivityScenario<MainActivity> run = ActivityScenario.launch(MainActivity.class)) {
            settle(); run.onActivity(a -> {
                freeze(a); publicWallet(a); SharedPreferences p=(SharedPreferences)get(a,"prefs");
                for (float bad : new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1,0,51}) {
                    p.edit().putFloat("buy_slippage_pct",bad).commit(); assertEquals(.042,(Double)call(a,"buySlippage"),0);
                    Map<String,?> before=new HashMap<>(p.getAll()); call(a,"setBuySlippage",new Class<?>[]{double.class},Double.NaN); assertEquals(before,p.getAll());
                }
                call(a,"setBuySlippage",new Class<?>[]{double.class},0d); assertEquals(.1f,p.getFloat("buy_slippage_pct",0),0);
                assertEquals(.1f/100d,(Double)call(a,"buySlippage"),0);
                call(a,"setBuySlippage",new Class<?>[]{double.class},100d); assertEquals(50f,p.getFloat("buy_slippage_pct",0),0); assertEquals(.5,(Double)call(a,"buySlippage"),0);
                stopTicks(a);
            });
        }
    }
}
