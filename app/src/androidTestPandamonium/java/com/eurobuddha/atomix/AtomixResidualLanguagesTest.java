package com.eurobuddha.atomix;

import android.view.View;
import android.widget.*;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public fixture data and stopped workers; no seed, signing, node or transfer. */
public class AtomixResidualLanguagesTest extends AtomixMarketLanguagesTest {
    private static final String[] LANGS={"en","zh-Hans","ru","uk"};
    private static final String PLACEHOLDER="— (node didn't answer)";
    @Test public void balancePlaceholderTranslatesWithoutChangingRawBalanceOrNumericFormatting() throws Exception {
        try(ActivityScenario<MainActivity> run=ActivityScenario.launch(MainActivity.class)) {
            settle(); run.onActivity(a->{freeze(a);publicWallet(a);});
            for(TradingContext market:TradingContext.values())for(Design.Mode mode:Design.Mode.values()) {
                AtomicReference<View> kept=new AtomicReference<>();
                run.onActivity(a->{TradingContext.setActive(market,null);Design.set(a,mode);call(a,"applyChrome");set(a,"minimaBal",PLACEHOLDER);LinearLayout col=AtomixHistoryLanguagesTest.blank(a);call(a,"renderWalletTab",new Class<?>[]{LinearLayout.class},col);kept.set(page(a));stopTicks(a);});
                for(String lang:LANGS) {
                    run.onActivity(a->Language.select(a,lang));settle();
                    run.onActivity(a->{assertSame(kept.get(),page(a));assertEquals(PLACEHOLDER,get(a,"minimaBal"));wrapped(exact(page(a),Language.join(Language.msg(l10n_atomix_notice_node_no_answer)," ",market.coinLabel)));stopTicks(a);});
                }
                if(market==TradingContext.MINIMA)screenshot("atomix-residual-balance-"+mode.name().toLowerCase(java.util.Locale.ROOT));
                run.onActivity(a->{set(a,"minimaBal","00012.34567890");LinearLayout col=AtomixHistoryLanguagesTest.blank(a);call(a,"renderWalletTab",new Class<?>[]{LinearLayout.class},col);exact(page(a),"12.34567 "+market.coinLabel);assertEquals("00012.34567890",get(a,"minimaBal"));stopTicks(a);});
            }
        }
    }
    @Test public void swapErrorTranslatesKnownReasonAndPreservesStoredAndUnknownEvidence() throws Exception {
        try(ActivityScenario<MainActivity> run=ActivityScenario.launch(MainActivity.class)) {
            settle();run.onActivity(a->{freeze(a);publicWallet(a);});
            int i=0;
            for(String raw:new String[]{"their USDT lock is too close to its timeout","invalid node reply 中文 Русский Українська 0xAbCd $1 {amount}"}) {
                String hash="0x"+(i++==0?"91":"92").repeat(32);AtomicReference<View> kept=new AtomicReference<>();
                try {
                    run.onActivity(a->{SwapDb db=(SwapDb)get(a,"db");db.logEvent(hash,"FIXTURE_ERROR","USDT","00012.34000",raw);LinearLayout col=AtomixHistoryLanguagesTest.blank(a);col.addView((View)call(a,"swapCard",new Class<?>[]{SwapDb.Swap.class},AtomixHistoryLanguagesTest.fixture(hash,"MINIMA",SwapDb.ST_ERROR,"INITIATOR")));kept.set(page(a));stopTicks(a);});
                    for(String lang:LANGS) {
                        run.onActivity(a->Language.select(a,lang));settle();
                        run.onActivity(a->{assertSame(kept.get(),page(a));exact(page(a),Language.join("⚠ ",AtomixMessages.message(raw)));assertEquals(raw,((SwapDb)get(a,"db")).getEvents(hash).get(0).note);assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());stopTicks(a);});
                    }
                    screenshot("atomix-residual-error-"+i);
                } finally {
                    run.onActivity(a->{SwapDb db=(SwapDb)get(a,"db");((SQLiteOpenHelper)get(db,"helper")).getWritableDatabase().delete("events","hash=?",new String[]{hash.substring(2)});assertTrue(db.getEvents(hash).isEmpty());stopTicks(a);});
                }
            }
        }
    }
    @Test public void coinReportKeepsMissingBalanceAsTranslatedUnknown() throws Exception {
        try(ActivityScenario<MainActivity> run=ActivityScenario.launch(MainActivity.class)) {
            settle();AtomicReference<AlertDialog> kept=new AtomicReference<>();
            run.onActivity(a->{freeze(a);publicWallet(a);AtomixReportLanguagesTest.fixture(a,TradingContext.MINIMA);set(a,"minimaBal",PLACEHOLDER);call(a,"minimaCoinDump");});settle();run.onActivity(a->{kept.set(dialog(a));stopTicks(a);});
            for(String lang:LANGS) {
                run.onActivity(a->Language.select(a,lang));settle();
                run.onActivity(a->{assertSame(kept.get(),dialog(a));boolean found=false;for(TextView t:texts(kept.get().getWindow().getDecorView()))if(t.getText().toString().contains(Language.msg(l10n_atomix_notice_node_no_answer))){found=true;assertTrue(t.isTextSelectable());}assertTrue(found);assertEquals(PLACEHOLDER,get(a,"minimaBal"));stopTicks(a);});
            }
            screenshot("atomix-residual-report");run.onActivity(a->{kept.get().dismiss();stopTicks(a);});
        }
    }
}
