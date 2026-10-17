package com.eurobuddha.atomix;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.atomix.swap.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Inherits seedless-device guards and workers stopped before any form action. */
public class AtomixAmountsLanguagesTest extends AtomixCurrencyLanguagesTest {
 @Test public void oversizedAvailabilityStaysADraftAndFundsErrorsTranslate(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<Map<String,?>>saved=new AtomicReference<>();AtomicReference<View>kept=new AtomicReference<>();AtomicReference<EditText>sell=new AtomicReference<>();String huge="9".repeat(340);
   s.onActivity(a->{freeze(a);publicWallet(a);call(a,"setTab",new Class<?>[]{int.class},4);kept.set(page(a));sell.set(fields(page(a)).get(0));sell.get().setText(huge);sell.get().requestFocus();sell.get().setSelection(13);saved.set(new HashMap<>(((SharedPreferences)get(a,"prefs")).getAll()));});
   for(String lang:new String[]{"en","zh-Hans","ru","uk"}){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(page(a),Language.msg(l10n_atomix_otc_go_live)).performClick();assertSame(kept.get(),page(a));assertEquals(huge,sell.get().getText().toString());assertEquals(13,sell.get().getSelectionStart());assertEquals(saved.get(),((SharedPreferences)get(a,"prefs")).getAll());for(String bad:new String[]{"NaN","Infinity",huge,"0.0000001"}){Object error=call(a,"otcCheckFunds",new Class<?>[]{String.class,String.class,String.class,String.class},OtcDb.ROLE_INSTIGATOR,OtcOffer.LP_BUYS_MINIMA,bad,"1");assertEquals(Language.msg(l10n_atomix_otc_enter_terms).toString(),error.toString());}assertNull(call(a,"otcCheckFunds",new Class<?>[]{String.class,String.class,String.class,String.class},OtcDb.ROLE_INSTIGATOR,OtcOffer.LP_BUYS_MINIMA,"0001.0000","1"));});}
   s.onActivity(a->blur(a));
  }
 }
}
