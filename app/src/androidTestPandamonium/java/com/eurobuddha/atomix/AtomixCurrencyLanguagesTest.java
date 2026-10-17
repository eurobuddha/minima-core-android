package com.eurobuddha.atomix;
import android.content.ContentValues;
import android.database.sqlite.SQLiteOpenHelper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses the seedless/emulator guards. Only unique public fixture rows are inserted/deleted. */
public class AtomixCurrencyLanguagesTest extends AtomixOtcLanguagesTest {
 private TradingContext saved;
 private final List<String>refs=new ArrayList<>();private OtcDb fixtureDb;
 @Before public void saveMarket(){saved=TradingContext.active();}
 @After public void restoreMarket(){TradingContext.setActive(saved,null);if(fixtureDb!=null)for(String ref:refs)fixtureDb.deleteDeal(ref);}
 OtcDb.Deal add(OtcDb db,String currency){OtcDb.Deal d=deal();d.currency=currency;db.upsertDeal(d);refs.add(d.ref);return d;}
 static boolean contains(View v,CharSequence label){for(TextView t:texts(v))if(label.toString().equals(t.getText().toString()))return true;return false;}
 @Test public void eachDealKeepsItsOwnCurrencyAndUnknownRowsNeverAcquireOne()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<OtcDb.Deal>minima=new AtomicReference<>(),dollar=new AtomicReference<>(),legacy=new AtomicReference<>();
   s.onActivity(a->{freeze(a);publicWallet(a);fixtureDb=(OtcDb)get(a,"otcDb");minima.set(add(fixtureDb,"minima"));dollar.set(add(fixtureDb,"mxusdt"));legacy.set(add(fixtureDb,"future-market"));
    ContentValues values=new ContentValues();values.putNull("currency");assertEquals(1,((SQLiteOpenHelper)get(fixtureDb,"helper")).getWritableDatabase().update("otc_deals",values,"ref=?",new String[]{legacy.get().ref}));
    call(a,"setTab",new Class<?>[]{int.class},4);
   });
   for(TradingContext market:TradingContext.values()){
    s.onActivity(a->{TradingContext.setActive(market,null);set(a,"otcInputFocused",false);call(a,"render");blur(a);OtcDb.Deal old=fixtureDb.getDeal(legacy.get().ref);assertNull(old.currency);old.status=OtcDb.ST_COUNTERED;fixtureDb.upsertDeal(old);assertNull(fixtureDb.getDeal(old.ref).currency);});
    for(String lang:new String[]{"en","zh-Hans","ru","uk"}){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{for(TradingContext c:TradingContext.values()){View card=(View)exact(page(a),Language.msg(l10n_atomix_otc_buy_terms,"00012.34000",c.coinLabel,"0000.004200")).getParent();assertEquals(c==market,contains(card,Language.msg(l10n_atomix_otc_accept)));if(c!=market)exact(card,Language.msg(l10n_atomix_otc_switch_market,c.coinLabel));}View unknown=(View)exact(page(a),Language.msg(l10n_atomix_otc_buy_terms,"00012.34000",Language.msg(l10n_atomix_otc_unknown_market),"0000.004200")).getParent();assertFalse(contains(unknown,Language.msg(l10n_atomix_otc_accept)));exact(unknown,Language.msg(l10n_atomix_otc_unknown_market_help));assertEquals("minima",fixtureDb.getDeal(minima.get().ref).currency);assertEquals("mxusdt",fixtureDb.getDeal(dollar.get().ref).currency);assertNull(fixtureDb.getDeal(legacy.get().ref).currency);((ScrollView)get(a,"scroller")).fullScroll(View.FOCUS_DOWN);});if("uk".equals(lang))screenshot("atomix-currency-deals-"+market.key);}
   }
   s.onActivity(a->{OtcDb.Deal inactive=TradingContext.active()==TradingContext.MINIMA?dollar.get():minima.get();call(a,"otcCounterDialog",new Class<?>[]{OtcDb.Deal.class},inactive);assertTrue(dialogs(a).isEmpty());});
  }
 }
 @Test public void counterConfirmedAfterMarketChangeCannotAlterStoredDeal()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<OtcDb.Deal>terms=new AtomicReference<>();AtomicReference<AlertDialog>d=new AtomicReference<>();
   s.onActivity(a->{freeze(a);publicWallet(a);fixtureDb=(OtcDb)get(a,"otcDb");terms.set(add(fixtureDb,TradingContext.active().key));call(a,"setTab",new Class<?>[]{int.class},4);call(a,"render");blur(a);call(a,"otcCounterDialog",new Class<?>[]{OtcDb.Deal.class},terms.get());d.set(dialog(a));inputs(d.get()).get(0).setText("2");inputs(d.get()).get(1).setText("3");TradingContext.setActive(TradingContext.active().other(),null);});
   for(String lang:new String[]{"en","zh-Hans","ru","uk"}){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals("2",inputs(d.get()).get(0).getText().toString());});}
   s.onActivity(a->d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();s.onActivity(a->{assertTrue(dialogs(a).isEmpty());OtcDb.Deal stored=fixtureDb.getDeal(terms.get().ref);assertEquals("00012.34000",stored.amount);assertEquals("0000.004200",stored.price);assertEquals(OtcDb.ST_PROPOSED,stored.status);assertEquals(terms.get().currency,stored.currency);assertTrue(fixtureDb.msgs(stored.ref).isEmpty());});
  }
 }
}
