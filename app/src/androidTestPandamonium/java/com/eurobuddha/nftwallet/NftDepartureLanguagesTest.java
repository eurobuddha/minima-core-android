package com.eurobuddha.nftwallet;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.nftwallet.NftActionsLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Refusal and unknown-evidence displays only; the fake node rejects every transaction. */
@RunWith(AndroidJUnit4.class)
public class NftDepartureLanguagesTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();boolean authorized;
    static final long ID=987654322L;
    @Before public void prepare(){guards.prepare();authorized=true;}
    @After public void reset(){if(authorized)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
        JSONArray rows=LocalStore.load(context);assertTrue(rows.length()<=1);
        if(rows.length()==1){assertEquals(ID,rows.optJSONObject(0).optLong("id"));assertEquals(TOKEN,rows.optJSONObject(0).optString("tokenid"));LocalStore.save(context,new JSONArray());}
    });guards.reset();}
    @Test public void unfinishedMintRefusalSwitchesLanguageWithoutStartingCommands()throws Exception{screen(0);}
    @Test public void unknownReceiptRemainsUnconfirmedInEveryLanguage()throws Exception{screen(1);}
    @Test public void malformedItemRefusalSwitchesLanguageWithoutStartingCommands()throws Exception{screen(2);}
    void screen(int mode)throws Exception{
        try(ActivityScenario<MainActivity>s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<Dialog>d=new AtomicReference<>();AtomicReference<String> stored=new AtomicReference<>();
            s.onActivity(a->{
                n.set(fixture(a));
                if(mode==0)LocalStore.save(a,new JSONArray().put(object("id",ID,"tokenid",TOKEN,"phase","NEEDIMAGES","name","Public fixture","items",new JSONArray())));
                stored.set(a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));
                if(mode==1){Sheet.Progress p=Sheet.progress(a,Language.msg(l10n_nft_actions_transferring_name,ITEM),"");invoke("confirmDeparture",new Class<?>[]{MainActivity.class,String.class,JSONObject.class,String.class,JSONObject.class,Sheet.Progress.class,CharSequence.class},a,TOKEN,object(),ADDRESS,object("status",true),p,"NEVER CONFIRMED");}
                else invoke("runBury",new Class<?>[]{MainActivity.class,String.class,CharSequence.class,JSONObject.class},a,TOKEN,ITEM,coin(mode==2?"0":"7"));
                d.set(dialog(a));assertTrue(n.get().requests.isEmpty());
            });settle();android.os.SystemClock.sleep(12000);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(d.get(),dialog(a));View root=d.get().getWindow().getDecorView();exact(root,Language.msg(mode==0?l10n_nft_actions_mint_unfinished:mode==1?l10n_nft_actions_output_unverified:l10n_nft_actions_invalid_departure));bounds(exact(root,Language.msg(l10n_language_close)));
                assertTrue(n.get().requests.isEmpty());assertEquals(stored.get(),a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));
            });guards.screenshot("nft-departure-"+mode+"-"+code);}
            s.onActivity(a->close(a,l10n_language_close));settle();
        }
    }
}
