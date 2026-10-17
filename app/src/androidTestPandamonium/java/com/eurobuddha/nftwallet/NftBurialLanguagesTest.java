package com.eurobuddha.nftwallet;
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
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public display-only paused/completed records on the disposable test emulator. No transaction is submitted. */
@RunWith(AndroidJUnit4.class)
public class NftBurialLanguagesTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final long ID=987654321L;static final String TOKEN="0x"+"ab".repeat(32);
    boolean authorized;
    @Before public void prepare(){guards.prepare();authorized=true;}
    @After public void reset(){
        if(authorized)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            JSONArray rows=LocalStore.load(context);assertTrue(rows.length()<=1);
            if(rows.length()==1){assertEquals(ID,rows.optJSONObject(0).optLong("id"));assertEquals(TOKEN,rows.optJSONObject(0).optString("tokenid"));LocalStore.save(context,new JSONArray());}
        });guards.reset();
    }
    @Test public void pausedMintKeepsItsRecordAndShowsLiveRetryAndExplanation()throws Exception{screen("BURY");}
    @Test public void completedBurialShowsVerifiedOutcomeWithoutResumeOrBuryActions()throws Exception{screen("BURIED");}
    void screen(String phase)throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> root=new AtomicReference<>();AtomicReference<String> stored=new AtomicReference<>();
            scenario.onActivity(a->{
                node.set(freeze(a));JSONObject row=object("id",ID,"tokenid",TOKEN,"name","Public collection 中文 Русский Українська","phase",phase,"size",1,"mode","url","items",new JSONArray(),"error","");LocalStore.save(a,new JSONArray().put(row));
                MintView mint=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];set(mint,"progressRowId",ID);Object screen=get(mint,"screen");set(mint,"screen",Enum.valueOf((Class)screen.getClass(),"PROGRESS"));a.goToTab(MainActivity.TAB_MINT);mint.refresh();root.set(mint.getRoot());node.get().requests.clear();stored.set(a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));
            });settle();android.os.SystemClock.sleep(12000);
            for(String code:new String[]{"en","zh-Hans","ru","uk"}){
                scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                    exact(root.get(),Language.msg(phase.equals("BURY")?l10n_nft_burial_paused:l10n_nft_burial_confirmed));
                    if(phase.equals("BURY"))NftSendLanguagesTest.visibleAction(exact(root.get(),Language.msg(l10n_nft_burial_check_retry)));
                    for(android.widget.TextView text:texts(root.get()))assertNotEquals(MintMessages.message("Resume now").toString(),text.getText().toString());
                    assertEquals(stored.get(),a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));assertEquals(phase,LocalStore.findById(a,ID).optString("phase"));assertTrue(node.get().requests.isEmpty());assertFalse(MintDriver.hasWork(a));
                });guards.screenshot("nft-burial-"+phase+"-"+code);
            }
        }
    }
}
