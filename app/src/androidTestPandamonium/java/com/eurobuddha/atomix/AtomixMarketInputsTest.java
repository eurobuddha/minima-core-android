package com.eurobuddha.atomix;
import android.content.SharedPreferences;
import android.graphics.Rect;
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
/** Inherits seedless/no-node/oracle isolation and the existing market/shell language regressions. */
public class AtomixMarketInputsTest extends AtomixMarketLanguagesTest {
 private static final String HUGE="9".repeat(340),FINITE="9".repeat(200);
 private static List<String> values(List<EditText> fields,int start,int end){List<String> out=new ArrayList<>();for(int i=start;i<end;i++)out.add(fields.get(i).getText().toString());return out;}
 private static SharedPreferences prefs(MainActivity a){return (SharedPreferences)get(a,"prefs");}
 @Test public void everyInvalidFieldRetainsTheDraftAndCannotChangeSettings()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();AtomicReference<List<EditText>>fields=new AtomicReference<>();AtomicReference<Map<String,?>>saved=new AtomicReference<>();
   s.onActivity(a->{freeze(a);publicWallet(a);set(a,"currentTab",3);call(a,"render");call(a,"editOrderDialog");stopTicks(a);d.set(dialog(a));fields.set(inputs(d.get()));saved.set(new HashMap<>(prefs(a).getAll()));
    for(EditText field:fields.get()){String before=field.getText().toString();field.setText(HUGE);field.setSelection(7);d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertSame(d.get(),dialog(a));assertEquals(HUGE,field.getText().toString());assertEquals(7,field.getSelectionStart());exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_invalid_number));assertEquals(saved.get(),prefs(a).getAll());field.setText(before);}
    String max=java.math.BigDecimal.valueOf(Double.MAX_VALUE).toPlainString();EditText reprice=fields.get().get(1);String oldReprice=reprice.getText().toString();reprice.setText(max);d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertSame(d.get(),dialog(a));assertEquals(max,reprice.getText().toString());assertEquals(saved.get(),prefs(a).getAll());reprice.setText(oldReprice);
    fields.get().get(7).setText(".");d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertSame(d.get(),dialog(a));assertEquals(".",fields.get().get(7).getText().toString());fields.get().get(7).setText("0."+"0".repeat(340)+"1");fields.get().get(7).setSelection(8);d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
   });
   for(String lang:new String[]{"en","zh-Hans","ru","uk"}){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals("0."+"0".repeat(340)+"1",fields.get().get(7).getText().toString());assertEquals(8,fields.get().get(7).getSelectionStart());TextView error=exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_invalid_number));Rect rect=new Rect();assertTrue(lang+" validation error is outside viewport",error.getGlobalVisibleRect(rect));assertTrue(lang+" validation error has no visible height",rect.height()>0);assertEquals(saved.get(),prefs(a).getAll());});}
   screenshot("atomix-market-input-error");s.onActivity(a->d.get().cancel());settle();s.onActivity(a->{stopTicks(a);assertFalse((Boolean)get(a,"modalOpen"));assertNull(get(a,"pegTickRef"));assertEquals(saved.get(),prefs(a).getAll());});
  }
 }
 @Test public void calculatedOverflowPreservesManualAndPeggedRows()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();s.onActivity(a->{freeze(a);publicWallet(a);set(a,"currentTab",3);call(a,"render");});
   for(boolean peg:new boolean[]{false,true}){
    s.onActivity(a->{call(a,"editOrderDialog");stopTicks(a);AlertDialog d=dialog(a);List<EditText> fields=inputs(d);hint(d,l10n_atomix_market_mid).setText("1");hint(d,l10n_atomix_market_step).setText("0.25");hint(d,l10n_atomix_market_ask_size).setText("2");hint(d,l10n_atomix_market_bid_size).setText("3");hint(d,l10n_atomix_market_levels).setText("2");Map<String,?>saved=new HashMap<>(prefs(a).getAll());List<String>before;
     if(peg){hint(d,l10n_atomix_market_skew).setText("20");before=values(fields,2,31);set(PriceOracle.class,null,"price",Double.MAX_VALUE);set(PriceOracle.class,null,"goodAtMs",System.currentTimeMillis());exact(d.getWindow().getDecorView(),Language.msg(l10n_atomix_market_peg,PriceOracle.source())).performClick();assertEquals(before,values(fields,2,31));}
     else{hint(d,l10n_atomix_market_mid).setText(FINITE);before=values(fields,7,31);hint(d,l10n_atomix_market_ask_size).setText(FINITE);assertEquals(before,values(fields,7,31));}
     exact(d.getWindow().getDecorView(),Language.msg(l10n_atomix_market_invalid_number));d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertSame(d,dialog(a));assertEquals(saved,prefs(a).getAll());d.cancel();set(PriceOracle.class,null,"price",0d);stopTicks(a);
    });settle();
   }
  }
 }
 @Test public void validSaveAndClearKeepExistingOrderSemanticsAfterLanguageChanges()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<AlertDialog>d=new AtomicReference<>();s.onActivity(a->{freeze(a);publicWallet(a);assertFalse(prefs(a).getBoolean("auto_publish",false));set(a,"currentTab",3);call(a,"render");call(a,"editOrderDialog");stopTicks(a);d.set(dialog(a));List<EditText>fields=inputs(d.get());fields.get(7).setText("3");fields.get(8).setText("5");fields.get(17).setText("2.0000");fields.get(18).setText("00012.34000");fields.get(31).setText("0.1");exact(d.get().getWindow().getDecorView(),Language.msg(l10n_atomix_market_enabled)).performClick();});
   for(String lang:new String[]{"en","zh-Hans","ru","uk"}){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertSame(d.get(),dialog(a));assertEquals("00012.34000",inputs(d.get()).get(18).getText().toString());});}
   s.onActivity(a->{boolean cleared=false;for(TextView v:texts(d.get().getWindow().getDecorView()))if(Language.msg(l10n_atomix_market_clear,"A6").toString().contentEquals(v.getContentDescription()==null?"":v.getContentDescription())){v.performClick();cleared=true;break;}assertTrue(cleared);d.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();s.onActivity(a->{stopTicks(a);assertTrue(dialogs(a).isEmpty());assertFalse((Boolean)get(a,"modalOpen"));assertNull(get(a,"pegTickRef"));Order.Pair pair=Order.fromConfigJson(prefs(a).getString("order_config","")).pairs.get("USDT");assertTrue(pair.enable);assertEquals(1,pair.asks.size());assertEquals(2,pair.asks.get(0).price,0);assertEquals(12.34,pair.asks.get(0).amount,0);assertEquals(0.1,pair.min,0);assertTrue(pair.bids.isEmpty());assertFalse(prefs(a).getBoolean(PriceOracle.P_ENABLE,true));assertFalse(prefs(a).getBoolean("auto_publish",false));assertTrue(((SwapDb)get(a,"db")).allSwaps().isEmpty());});
  }
 }
}
