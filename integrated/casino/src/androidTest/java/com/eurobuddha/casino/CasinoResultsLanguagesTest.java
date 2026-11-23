package com.eurobuddha.casino;
import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Reuses the disposable Casino fixture and the three shell regression cases. No real node or signing. */
public class CasinoResultsLanguagesTest extends CasinoLanguagesTest {
 private static final String[] LANGS={"en","zh-Hans","ru","uk"};
 private static Bet active(int phase,String token){
  Coin c=new Coin();c.coinid="public-fixture-"+phase;c.tokenid=token;c.amount="24.680000";c.created=100;
  c.state.put(CasinoContract.P_HOUSE_PK,"public-house");c.state.put(CasinoContract.P_PLAYER_PK,"public-player");c.state.put(CasinoContract.P_HOUSE_COMMIT,"public-commit");c.state.put(CasinoContract.P_RANGE,"2");c.state.put(CasinoContract.P_PAYOUT,"2");c.state.put(CasinoContract.P_BET,"12.340000");c.state.put(CasinoContract.P_TIMEOUT,"10");c.state.put(CasinoContract.P_PHASE,Integer.toString(phase));c.state.put(CasinoContract.P_PICK,phase==0?"-1":"1");return new Bet(c);
 }
 private static <T extends View>T first(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){T found=first(g.getChildAt(i),type);if(found!=null)return found;}}return null;}
 private static ResolvedBet row(String id,String token,boolean won,String amount){ResolvedBet r=new ResolvedBet();r.coinid=id;r.tokenid=token;r.role="Player";r.game="Coin Flip";r.range=2;r.pickLabel="Tails";r.resultLabel=won?"Tails":"Heads";r.pickIdx=1;r.resultIdx=won?1:0;r.won=won;r.profit=amount;r.time=1767227400000L;r.celebrated=true;return r;}
 private static boolean has(View v,CharSequence text){for(TextView t:texts(v))if(text.toString().equals(t.getText().toString()))return true;return false;}
 @Test public void activeBetRetainsAnimationAndStrictTimeoutBoundary()throws Exception{
  assertTrue("Animation tests require animator_duration_scale > 0 on the disposable emulator", ValueAnimator.areAnimatorsEnabled());
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView> page=new AtomicReference<>();AtomicReference<CoinFlipView> coin=new AtomicReference<>();AtomicReference<Object> anim=new AtomicReference<>();Bet bet=active(1,"0x00");Map<Integer,String> raw=new HashMap<>(bet.coin.state);
   s.onActivity(a->{node.set(freeze(a));a.myKeys().add("public-player");a.bets().add(bet);set(a,"chainBlock",109);a.switchTab(MainActivity.TAB_MYBETS);page.set(page(a,MainActivity.TAB_MYBETS));coin.set(first(page.get().getRoot(),CoinFlipView.class));assertNotNull(coin.get());anim.set(get(coin.get(),"anim"));node.get().commands.clear();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{View root=page.get().getRoot();assertSame(page.get(),page(a,MainActivity.TAB_MYBETS));assertSame(coin.get(),first(root,CoinFlipView.class));assertSame(anim.get(),get(coin.get(),"anim"));assertTrue("Language change stopped the existing coin animator", ((ValueAnimator)anim.get()).isRunning());exact(root,Language.msg(l10n_casino_role_player));exact(root,Language.msg(l10n_casino_picked,Language.msg(l10n_casino_tails)));exact(root,Language.join(Language.msg(l10n_casino_waiting_house),"\n",Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_timeout_left,2,2L,9L)));assertFalse(has(root,Language.msg(l10n_casino_claim_timeout)));assertEquals(raw,bet.coin.state);assertTrue(node.get().commands.isEmpty());});screenshot("casino-active-"+lang);}
   s.onActivity(a->{set(a,"chainBlock",110);page.get().refresh();assertSame(coin.get(),first(page.get().getRoot(),CoinFlipView.class));assertSame(anim.get(),get(coin.get(),"anim"));assertFalse(bet.canClaimTimeout(a.myKeys(),a.chainBlock()));exact(page.get().getRoot(),Language.join(Language.msg(l10n_casino_waiting_house),"\n",Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_timeout_left,1,1L,10L)));set(a,"chainBlock",111);page.get().refresh();assertTrue(bet.canClaimTimeout(a.myKeys(),a.chainBlock()));});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(page.get().getRoot(),Language.msg(l10n_casino_claim_timeout));exact(page.get().getRoot(),Language.join(Language.msg(l10n_casino_waiting_house),"\n",Language.msg(l10n_casino_claim_yours)));assertEquals(raw,bet.coin.state);assertTrue(node.get().commands.isEmpty());});}
  }
 }
 @Test public void houseAndSettledPlayerKeepActionsAndPayoutState(){
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView> page=new AtomicReference<>();Bet open=active(0,Currency.USD_TOKENID);Bet settled=active(2,Currency.USD_TOKENID);ResolvedBet result=row(settled.coinid(),Currency.USD_TOKENID,true,"12.34");String json=result.toJson().toString();
   s.onActivity(a->{node.set(freeze(a));a.myKeys().add("public-house");a.bets().add(open);a.switchTab(MainActivity.TAB_MYBETS);page.set(page(a,MainActivity.TAB_MYBETS));node.get().commands.clear();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(page.get().getRoot(),Language.msg(l10n_common_cancel));exact(page.get().getRoot(),Language.msg(l10n_casino_role_house));exact(page.get().getRoot(),Language.msg(l10n_casino_waiting_pick));exact(page.get().getRoot(),Language.msg(l10n_casino_open_status));assertTrue(node.get().commands.isEmpty());});}
   s.onActivity(a->{a.myKeys().clear();a.myKeys().add("public-player");a.bets().clear();a.bets().add(settled);a.history().add(result);set(a,"chainBlock",111);page.get().refresh();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(page.get().getRoot(),Language.msg(l10n_casino_payout_confirm,Language.msg(l10n_casino_won_amount,"12.34")));exact(page.get().getRoot(),Language.join(Language.msg(l10n_casino_resolving_payout),"\n",Language.msg(l10n_casino_claim_theirs)));assertFalse(has(page.get().getRoot(),Language.msg(l10n_casino_resolve)));assertFalse(has(page.get().getRoot(),Language.msg(l10n_casino_claim_timeout)));assertEquals(json,result.toJson().toString());assertTrue(node.get().commands.isEmpty());});}
  }
 }
 @Test public void historyPreservesCurrenciesRawRecordsTotalsAndRowCap()throws Exception{
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<BaseView> page=new AtomicReference<>();ResolvedBet win=row("win","0x00",true,"12.34"),loss=row("loss","0x00",false,"0.34"),usd=row("usd",Currency.USD_TOKENID,true,"999.123456");String raw=win.toJson().toString()+loss.toJson()+usd.toJson();AtomicReference<View> kept=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));a.history().addAll(Arrays.asList(win,loss,usd));a.switchTab(MainActivity.TAB_HISTORY);page.set(page(a,MainActivity.TAB_HISTORY));kept.set(((LinearLayout)get(page.get(),"container")).getChildAt(2));node.get().commands.clear();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{View root=page.get().getRoot();assertSame(kept.get(),((LinearLayout)get(page.get(),"container")).getChildAt(2));exact(root,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_history_count,2,2,"MINIMA"));exact(root,Language.msg(l10n_casino_history_net,"+12"));exact(root,Language.join(Language.msg(l10n_casino_coin_flip),"  ·  ",Language.msg(l10n_casino_history_player)));exact(root,Language.msg(l10n_casino_history_detail,Language.msg(l10n_casino_tails),Language.msg(l10n_casino_heads),Language.stamp(loss.time,"MMMdHm")));assertEquals(raw,win.toJson().toString()+loss.toJson()+usd.toJson());assertTrue(node.get().commands.isEmpty());});screenshot("casino-history-"+lang);}
   s.onActivity(a->{Theme.setDollar(a,true);page.get().refresh();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{exact(page.get().getRoot(),Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_history_count,1,1,"USD"));exact(page.get().getRoot(),Language.msg(l10n_casino_history_net,"+999.123456 USD"));assertEquals(raw,win.toJson().toString()+loss.toJson()+usd.toJson());assertTrue(node.get().commands.isEmpty());});}
   s.onActivity(a->{Theme.setDollar(a,false);a.history().clear();for(int i=0;i<251;i++)a.history().add(row("cap-"+i,"0x00",true,"1"));page.get().refresh();});
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{LinearLayout root=(LinearLayout)get(page.get(),"container");assertEquals(253,root.getChildCount());exact(root,Language.msg(l10n_casino_history_net,"+251"));exact(root,Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_casino_history_older,1));assertEquals(251,a.history().size());assertTrue(node.get().commands.isEmpty());});}
  }
 }
 @Test public void resultOverlayRebindsWithoutRestartingReveal()throws Exception{
  assertTrue("Animation tests require animator_duration_scale > 0 on the disposable emulator", ValueAnimator.areAnimatorsEnabled());
  try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
   settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> overlay=new AtomicReference<>();AtomicReference<CoinFlipView> coin=new AtomicReference<>();AtomicReference<Object> animation=new AtomicReference<>();
   java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
   s.onActivity(a->{node.set(freeze(a));ResultOverlay.show(a,CasinoContract.Game.FLIP,1,1,false,"12.340000",Currency.USD_TOKENID);ViewGroup root=a.findViewById(android.R.id.content);overlay.set(root.getChildAt(root.getChildCount()-1));coin.set(first(overlay.get(),CoinFlipView.class));node.get().commands.clear();
    // Reuse ResultOverlay's layout-listener timing: this runs after its own listener starts the reveal.
    // ActivityScenario's next onActivity waits for idle and can miss the entire 1100ms animation.
    coin.get().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
     @Override public void onGlobalLayout(){
      if(coin.get().getWidth()<=0||coin.get().getHeight()<=0)return;
      coin.get().getViewTreeObserver().removeOnGlobalLayoutListener(this);
      try{animation.set(get(coin.get(),"anim"));assertNotNull(animation.get());assertTrue("Reveal must start before changing language",((ValueAnimator)animation.get()).isRunning());Language.select(a,"zh-Hans");assertSame(animation.get(),get(coin.get(),"anim"));assertTrue("Language selection must not stop the reveal",((ValueAnimator)animation.get()).isRunning());}
      catch(Throwable t){failure.set(t);}finally{started.countDown();}
     }
    });
   });
   assertTrue("Reveal layout callback timed out",started.await(10,java.util.concurrent.TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());SystemClock.sleep(1700);settle();
   for(String lang:LANGS){s.onActivity(a->Language.select(a,lang));settle();s.onActivity(a->{assertNotNull(overlay.get().getParent());assertSame(coin.get(),first(overlay.get(),CoinFlipView.class));assertSame(animation.get(),get(coin.get(),"anim"));assertFalse(((ValueAnimator)animation.get()).isRunning());assertEquals(1,get(coin.get(),"result"));assertEquals(Language.msg(l10n_casino_head_glyph).toString(),get(coin.get(),"headsGlyph"));assertEquals(Language.msg(l10n_casino_tail_glyph).toString(),get(coin.get(),"tailsGlyph"));assertEquals(View.VISIBLE,exact(overlay.get(),Language.msg(l10n_casino_you_lose)).getVisibility());exact(overlay.get(),Language.msg(l10n_casino_tap_continue));TextView details=exact(overlay.get(),Language.msg(l10n_casino_result_detail,"-12.34 USD",Language.msg(l10n_casino_tails),Language.msg(l10n_casino_tails)));android.text.Layout layout=details.getLayout();assertNotNull(layout);assertEquals(details.getText().length(),layout.getLineEnd(layout.getLineCount()-1));assertTrue("Result detail lines must not be clipped in "+lang,details.getHeight()-details.getCompoundPaddingTop()-details.getCompoundPaddingBottom()>=layout.getLineBottom(layout.getLineCount()-1));assertTrue(a.history().isEmpty());assertTrue(node.get().commands.isEmpty());});}
   screenshot("casino-result-uk");s.onActivity(a->{overlay.get().performClick();assertNull(overlay.get().getParent());});
  }
 }
}
