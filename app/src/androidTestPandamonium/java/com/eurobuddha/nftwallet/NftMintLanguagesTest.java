package com.eurobuddha.nftwallet;
import android.content.Context;
import android.view.View;
import android.widget.*;
import android.app.Dialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.nftwallet.NftShellLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Retained views and public fixture records only. Never confirms a mint or starts a service. */
@RunWith(AndroidJUnit4.class)
public class NftMintLanguagesTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final long ID=987654323L;
    static final String[] CODES={"en","zh-Hans","ru","uk"};
    boolean authorized;
    @Before public void prepare(){guards.prepare();authorized=true;}
    @After public void reset(){
        if(authorized)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            JSONArray rows=LocalStore.load(context);assertTrue(rows.length()<=1);
            if(rows.length()==1){assertEquals(ID,rows.optJSONObject(0).optLong("id"));LocalStore.save(context,new JSONArray());}
        });guards.reset();
    }
    static MintView mint(MainActivity a,String page){
        MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];
        Object screen=get(m,"screen");set(m,"screen",Enum.valueOf((Class)screen.getClass(),page));a.goToTab(MainActivity.TAB_MINT);m.refresh();return m;
    }
    static void value(MintView m,String name,String text){((EditText)get(m,name)).setText(text);}
    @Test public void tokenDraftAndLiteralMetadataSurviveReviewAndLanguageChanges()throws Exception{form("TOKEN");}
    @Test public void nftDraftImageModeAndCreatorSurviveReviewAndLanguageChanges()throws Exception{form("NFT");}
    @Test public void collectionDraftAndImageModeSurviveReviewAndLanguageChanges()throws Exception{form("COLLECTION");}
    void form(String page)throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<MintView> current=new AtomicReference<>();AtomicReference<EditText> name=new AtomicReference<>();AtomicReference<View> form=new AtomicReference<>();
            String prefix=page.equals("TOKEN")?"t":page.equals("NFT")?"n":"c";
            String formField=page.equals("TOKEN")?"tokenForm":page.equals("NFT")?"nftForm":"colForm";
            int hint=page.equals("TOKEN")?l10n_nft_mint_my_token:page.equals("NFT")?l10n_nft_mint_my_nft:l10n_nft_mint_collection_name_required;
            scenario.onActivity(a->{
                node.set(freeze(a));MintView m=mint(a,page);current.set(m);value(m,prefix+"Name","CREATE");value(m,prefix+"Desc","DONE");name.set((EditText)get(m,prefix+"Name"));name.get().setSelection(3);form.set((View)get(m,formField));
                if(page.equals("TOKEN")){
                    value(m,"tAmount","1234");call(m,"addMetaRow",new Class<?>[]{});LinearLayout row=(LinearLayout)((LinearLayout)get(m,"tMetaRows")).getChildAt(0);((EditText)row.getChildAt(0)).setText("MOVE");((EditText)row.getChildAt(1)).setText("STAMP");
                }else if(page.equals("NFT")){
                    call(m,"setNftMode",new Class<?>[]{boolean.class},false);value(m,"nEditions","1");value(m,"nUrl","https://example.com/public.png");value(m,"nCreator","DONE");
                }else{
                    call(m,"setColMode",new Class<?>[]{boolean.class},false);value(m,"cSize","2");value(m,"cBase","https://example.com/public/");value(m,"cExt",".png");
                }
                node.get().requests.clear();
            });
            for(String code:CODES){
                scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                    MintView m=current.get();assertSame(form.get(),get(m,formField));assertSame(name.get(),get(m,prefix+"Name"));assertEquals("CREATE",name.get().getText().toString());assertEquals(3,name.get().getSelectionStart());assertEquals("DONE",((EditText)get(m,prefix+"Desc")).getText().toString());
                    if(!page.equals("COLLECTION"))assertEquals(Language.msg(hint).toString(),name.get().getHint().toString());
                    exact(form.get(),Language.msg(page.equals("COLLECTION")?l10n_nft_mint_collection_name_required:l10n_nft_mint_name_required));
                    if(page.equals("NFT")){assertFalse((Boolean)get(m,"nftEmbedMode"));assertEquals("DONE",((EditText)get(m,"nCreator")).getText().toString());assertTrue(((CheckBox)get(m,"nSign")).isChecked());}
                    if(page.equals("COLLECTION"))assertFalse((Boolean)get(m,"colEmbedMode"));
                    assertTrue(node.get().requests.isEmpty());assertEquals(0,LocalStore.load(a).length());
                });guards.screenshot("nft-mint-form-"+page+"-"+code);
            }
            AtomicReference<Dialog> review=new AtomicReference<>();
            scenario.onActivity(a->{call(current.get(),page.equals("TOKEN")?"onReviewToken":page.equals("NFT")?"onReviewNft":"onReviewCollection",new Class<?>[]{});review.set(dialog(a));});settle();
            for(String code:CODES){
                scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                    assertSame(review.get(),dialog(a));View root=review.get().getWindow().getDecorView();exact(root,"CREATE");exact(root,"DONE");
                    exact(root,Language.msg(page.equals("TOKEN")?l10n_nft_mint_confirm_token:page.equals("NFT")?l10n_nft_mint_confirm_nft:l10n_nft_mint_confirm_collection));
                    if(page.equals("TOKEN")){exact(root,"MOVE");exact(root,"STAMP");exact(root,"1234");}
                    NftSendLanguagesTest.visibleAction(exact(root,Language.msg(l10n_explorer_back)));
                    assertTrue(node.get().requests.isEmpty());assertEquals(0,LocalStore.load(a).length());
                });guards.screenshot("nft-mint-review-"+page+"-"+code);
            }
            scenario.onActivity(a->exact(review.get().getWindow().getDecorView(),Language.msg(l10n_explorer_back)).performClick());
        }
    }
    @Test public void requiredNameErrorUpdatesWithoutSendingAnyCommand()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<MintView> current=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));current.set(mint(a,"TOKEN"));call(current.get(),"onReviewToken",new Class<?>[]{});node.get().requests.clear();});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{assertEquals(MintMessages.message("Name: required.").toString(),((TextView)get(current.get(),"tStatus")).getText().toString());assertTrue(node.get().requests.isEmpty());assertEquals(0,LocalStore.load(a).length());});}
        }
    }
    @Test public void stuckCollectionNeverClaimsItIsFullyMinted()throws Exception{progress("MOVE",true);}
    @Test public void missingImagesAndFinishedProgressKeepCanonicalStoredPhase()throws Exception{progress("NEEDIMAGES",false);progress("DONE",false);}
    void progress(String phase,boolean stuck)throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> root=new AtomicReference<>();AtomicReference<String> stored=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));LocalStore.save(a,new JSONArray().put(object("id",ID,"name","CREATE","phase",phase,"size",2,"mode","embed","stuck",stuck?1:0,"items",new JSONArray().put(object("idx",1,"image","","coinid","")),"error","")));MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];set(m,"progressRowId",ID);mint(a,"PROGRESS");root.set(m.getRoot());node.get().requests.clear();stored.set(a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                exact(root.get(),"‹  CREATE");exact(root.get(),MintMessages.message("CREATE"));
                if(phase.equals("NEEDIMAGES"))exact(root.get(),Language.msg(l10n_nft_mint_paused_images));
                if(phase.equals("DONE"))exact(root.get(),Language.msg(l10n_nft_mint_fully_minted));
                if(stuck){exact(root.get(),Language.msg(l10n_nft_mint_stuck_help));for(TextView t:texts(root.get())){assertNotEquals(Language.msg(l10n_nft_mint_fully_minted).toString(),t.getText().toString());assertNotEquals(Language.msg(l10n_nft_mint_resume).toString(),t.getText().toString());}}
                assertEquals(stored.get(),a.getSharedPreferences("nftwallet_statenft",0).getString("collections",""));assertTrue(node.get().requests.isEmpty());
            });guards.screenshot("nft-mint-progress-"+phase+"-"+code);}
            scenario.onActivity(a->{assertEquals(ID,LocalStore.load(a).optJSONObject(0).optLong("id"));LocalStore.save(a,new JSONArray());});
        }
    }
}
