package com.eurobuddha.atomix;
import android.content.*;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** The inherited seedless-emulator guards stop all workers; no trade action is submitted. */
public class AtomixOtcLanguagesTest extends AtomixLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static final String AMOUNT="00012.34000", PRICE="0000.004200", KEY="0x"+"ab".repeat(64);
 static List<EditText> fields(View page){List<EditText> out=new ArrayList<>();for(TextView t:texts(page))if(t instanceof EditText)out.add((EditText)t);return out;}
 static void blur(MainActivity a){call(a,"finishOtcEditing",new Class<?>[]{View.class},page(a));((Handler)get(a,"ui")).removeCallbacksAndMessages(null);}
 static OtcOffer offer(){OtcOffer o=new OtcOffer();o.enable=true;o.sellSize=25.5;o.buySize=30.25;o.signerPk=KEY;o.commsPublicId="public OTC fixture";return o;}
 static OtcDb.Deal deal(){OtcDb.Deal d=new OtcDb.Deal();d.ref="language-otc-fixture-"+UUID.randomUUID();d.role=OtcDb.ROLE_INSTIGATOR;d.side=OtcOffer.LP_SELLS_MINIMA;d.amount=AMOUNT;d.price=PRICE;d.status=OtcDb.ST_PROPOSED;d.whoseTurn=OtcDb.TURN_ME;d.currency=TradingContext.active().key;return d;}
 @Test public void availabilityDraftSurvivesRefreshNavigationAndEveryLanguage()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);set(a,"currentTab",4);});
   for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<View> kept=new AtomicReference<>();AtomicReference<EditText> sell=new AtomicReference<>(),buy=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
    s.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");set(a,"otcInputFocused",false);call(a,"render");kept.set(page(a));List<EditText> f=fields(page(a));assertEquals(2,f.size());sell.set(f.get(0));buy.set(f.get(1));sell.get().setText(AMOUNT);buy.get().setText(PRICE);sell.get().requestFocus();sell.get().setSelection(5);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),page(a));assertTrue(sell.get().hasFocus());call(a,"render");assertSame(kept.get(),page(a));assertEquals(AMOUNT,sell.get().getText().toString());assertEquals(PRICE,buy.get().getText().toString());assertEquals(5,sell.get().getSelectionStart());assertEquals(Language.msg(l10n_atomix_otc_max_hint,TradingContext.active().coinLabel).toString(),sell.get().getHint().toString());exact(page(a),Language.msg(l10n_atomix_otc_availability));wrapped(exact(page(a),Language.msg(l10n_atomix_otc_go_live)));assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());});}
    s.onActivity(a->{blur(a);call(a,"setTab",new Class<?>[]{int.class},1);call(a,"setTab",new Class<?>[]{int.class},4);assertEquals(AMOUNT,fields(page(a)).get(0).getText().toString());assertEquals(PRICE,fields(page(a)).get(1).getText().toString());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());blur(a);});screenshot("atomix-otc-draft-"+mode.name().toLowerCase(Locale.ROOT));
   }
   s.onActivity(a->{fields(page(a)).get(0).requestFocus();IdentityWatch.setMismatchForTest(true,true);call(a,"render");exact(page(a),Language.msg(l10n_atomix_ui_mismatch_title));assertTrue(IdentityWatch.halted());});
  }
 }
 @Test public void offerAndCounterDialogsKeepTermsCursorsAndCancelSemantics()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);set(a,"currentTab",4);call(a,"render");blur(a);});
   for(boolean counter:new boolean[]{false,true})for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<List<EditText>>f=new AtomicReference<>();AtomicReference<OtcDb.Deal>terms=new AtomicReference<>();
    s.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");terms.set(deal());if(counter)call(a,"otcCounterDialog",new Class<?>[]{OtcDb.Deal.class},terms.get());else call(a,"otcProposeDialog",new Class<?>[]{OtcOffer.class,String.class},offer(),OtcOffer.LP_SELLS_MINIMA);d.set(dialog(a));f.set(inputs(d.get()));assertEquals(2,f.get().size());f.get().get(0).setText(AMOUNT);f.get().get(0).setSelection(4);f.get().get(1).setText(PRICE);f.get().get(1).setSelection(6);});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertTrue((Boolean)get(a,"modalOpen"));assertEquals(AMOUNT,f.get().get(0).getText().toString());assertEquals(PRICE,f.get().get(1).getText().toString());assertEquals(4,f.get().get(0).getSelectionStart());assertEquals(6,f.get().get(1).getSelectionStart());exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_otc_price,TradingContext.active().coinLabel));assertEquals(Language.msg(counter?l10n_atomix_otc_send_counter:l10n_atomix_otc_send_offer).toString(),d.get().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());assertEquals(AMOUNT,terms.get().amount);assertEquals(PRICE,terms.get().price);assertNull(((OtcDb)get(a,"otcDb")).getDeal(terms.get().ref));});}
    screenshot("atomix-otc-"+(counter?"counter":"offer")+"-"+mode.name().toLowerCase(Locale.ROOT));s.onActivity(a->{if(counter)d.get().cancel();else d.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();});settle();s.onActivity(a->{assertFalse((Boolean)get(a,"modalOpen"));assertTrue(dialogs(a).isEmpty());assertNull(((OtcDb)get(a,"otcDb")).getDeal(terms.get().ref));blur(a);});
   }
  }
 }
 @Test public void providersDealsAndFundsKeepPublicValuesAndStoredTerms()throws Exception{
  AtomicReference<OtcDb>db=new AtomicReference<>();AtomicReference<OtcDb.Deal>terms=new AtomicReference<>();
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);db.set((OtcDb)get(a,"otcDb"));terms.set(deal());assertNull(db.get().getDeal(terms.get().ref));db.get().upsertDeal(terms.get());((Map<String,OtcOffer>)get(a,"otcBook")).put(KEY,offer());set(a,"currentTab",4);call(a,"render");blur(a);});
   for(Design.Mode mode:Design.Mode.values()){
    AtomicReference<View>kept=new AtomicReference<>();s.onActivity(a->{Design.set(a,mode);call(a,"applyChrome");call(a,"render");blur(a);kept.set(page(a));});
    for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(kept.get(),page(a));String ccy=TradingContext.active().coinLabel;exact(page(a),Language.msg(l10n_atomix_otc_buy_terms,AMOUNT,ccy,PRICE));exact(page(a),Language.msg(l10n_atomix_otc_your_move));for(int id:new int[]{l10n_atomix_otc_accept,l10n_atomix_otc_counter,l10n_atomix_otc_reject})wrapped(exact(page(a),Language.msg(id)));OtcDb.Deal stored=db.get().getDeal(terms.get().ref);assertEquals(AMOUNT,stored.amount);assertEquals(PRICE,stored.price);assertEquals(terms.get().currency,stored.currency);assertEquals(OtcDb.ST_PROPOSED,stored.status);assertTrue(db.get().msgs(stored.ref).isEmpty());
     CharSequence shortfall=(CharSequence)call(a,"otcCheckFunds",new Class<?>[]{String.class,String.class,String.class,String.class},OtcDb.ROLE_INSTIGATOR,OtcOffer.LP_BUYS_MINIMA,"15","1");assertEquals(Language.msg(l10n_atomix_otc_funds,ccy,"15","12.34").toString(),shortfall.toString());assertNull(call(a,"otcCheckFunds",new Class<?>[]{String.class,String.class,String.class,String.class},OtcDb.ROLE_INSTIGATOR,OtcOffer.LP_BUYS_MINIMA,"10","1"));
     OtcDb.Deal unknown=deal();unknown.status="Status 中文 Русский Українська {amount}";assertEquals(unknown.status,call(a,"otcStatusText",new Class<?>[]{OtcDb.Deal.class},unknown));
     ((ScrollView)get(a,"scroller")).fullScroll(View.FOCUS_DOWN);
    });if("ru".equals(lang)||"uk".equals(lang))screenshot("atomix-otc-deals-"+mode.name().toLowerCase(Locale.ROOT)+"-"+lang);}
   }
   s.onActivity(a->{String shortKey=(String)call(a,"shortAddr",new Class<?>[]{String.class},KEY);exact(page(a),Language.msg(l10n_atomix_otc_provider,shortKey)).performClick();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(KEY,cm.getPrimaryClip().getItemAt(0).getText().toString());cm.clearPrimaryClip();});
  }finally{if(db.get()!=null&&terms.get()!=null)db.get().deleteDeal(terms.get().ref);}
 }
}
