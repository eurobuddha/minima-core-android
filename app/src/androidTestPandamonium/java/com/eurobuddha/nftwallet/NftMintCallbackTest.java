package com.eurobuddha.nftwallet;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.*;
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
import static com.eurobuddha.nftwallet.NftMintLanguagesTest.mint;
import static com.eurobuddha.nftwallet.NftMintLanguagesTest.value;
import static com.eurobuddha.minimacore.i18n.R.string.*;
/** Only public draft/PNG fixtures. Fake callbacks cannot reach a node or sign/post anything. */
@RunWith(AndroidJUnit4.class)
public class NftMintCallbackTest {
    final NftShellLanguagesTest guards=new NftShellLanguagesTest();
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final long FIRST=987654324L,SECOND=987654325L;
    boolean authorized;
    @Before public void prepare(){guards.prepare();authorized=true;}
    @After public void reset(){if(authorized){clearRows();assertFalse(MintDriver.isRunning());}guards.reset();}
    void clearRows(){JSONArray rows=LocalStore.load(context);for(int i=0;i<rows.length();i++){long id=rows.optJSONObject(i).optLong("id");assertTrue(id==FIRST||id==SECOND);}LocalStore.save(context,new JSONArray());}
    static class PendingNode extends FixtureNode {
        final List<Cb> creators=new ArrayList<>();PendingNode(Context c){super(c);}
        @Override public void cmd(String cmd,Cb cb){requests.add(cmd);if(cmd.equals("getaddress"))creators.add(cb);else{assertEquals("block",cmd);cb.onError("Public test node has no chain");}}
        void creator(int index){creators.get(index).onResult(object("status",true,"response",object("publickey","0x"+"ab".repeat(32),"address","0x"+"cd".repeat(32))));}
    }
    static PendingNode node(MainActivity a){freeze(a).onDestroy();PendingNode n=new PendingNode(a);set(a,"node",n);return n;}
    static MintView collection(MainActivity a){MintView m=mint(a,"COLLECTION");call(m,"setColMode",new Class<?>[]{boolean.class},false);value(m,"cName","PUBLIC ORIGINAL");value(m,"cSize","2");value(m,"cBase","https://example.com/public/");value(m,"cExt",".png");return m;}
    static void review(MintView m){call(m,"onReviewCollection",new Class<?>[]{});}
    static void submit(MainActivity a){exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_nft_mint_submit_collection)).performClick();}
    @Test public void changedReviewCannotStartCreatorLookup()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){settle();s.onActivity(a->{PendingNode n=node(a);MintView m=collection(a);review(m);value(m,"cName","EDITED AFTER REVIEW");submit(a);assertTrue(n.requests.isEmpty());assertEquals(0,LocalStore.load(a).length());assertEquals(Language.msg(l10n_nft_mint_review_again).toString(),((TextView)get(m,"cStatus")).getText().toString());});}
    }
    @Test public void delayedCreatorReplyPreservesEditedDraftAcrossLanguages()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<MintView> view=new AtomicReference<>();AtomicReference<PendingNode> n=new AtomicReference<>();
            s.onActivity(a->{n.set(node(a));MintView m=collection(a);view.set(m);review(m);submit(a);assertEquals(1,n.get().creators.size());value(m,"cName","NEW DRAFT 中文 Україна");((EditText)get(m,"cName")).setSelection(3);call(m,"setColMode",new Class<?>[]{boolean.class},true);((Map<Integer,String>)get(m,"colImages")).put(1,"PUBLIC NEW IMAGE");n.get().creator(0);});
            for(String code:NftMintLanguagesTest.CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{MintView m=view.get();assertEquals(Language.msg(l10n_nft_mint_review_again).toString(),((TextView)get(m,"cStatus")).getText().toString());assertEquals("NEW DRAFT 中文 Україна",((EditText)get(m,"cName")).getText().toString());assertEquals(3,((EditText)get(m,"cName")).getSelectionStart());assertTrue((Boolean)get(m,"colEmbedMode"));assertEquals("PUBLIC NEW IMAGE",((Map)get(m,"colImages")).get(1));assertEquals(0,LocalStore.load(a).length());assertEquals(Collections.singletonList("getaddress"),n.get().requests);});}
        }
    }
    @Test public void imagePickerResultAfterNavigationUpdatesOnlyCapturedCollection()throws Exception{
        java.io.File fixture=java.io.File.createTempFile("public-mint-image-",".png",context.getCacheDir());Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff2266aa);try(java.io.FileOutputStream out=new java.io.FileOutputStream(fixture)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();AtomicReference<PendingNode> n=new AtomicReference<>();AtomicReference<String> second=new AtomicReference<>();
            s.onActivity(a->{
                n.set(node(a));JSONObject first=object("id",FIRST,"name","PUBLIC FIRST","phase","NEEDIMAGES","mode","embed","size",2,"items",new JSONArray().put(object("idx",1,"image","","coinid","")).put(object("idx",2,"image","PUBLIC EXISTING","coinid","")));JSONObject other=object("id",SECOND,"name","PUBLIC SECOND","phase","NEEDIMAGES","mode","embed","size",2,"items",new JSONArray().put(object("idx",1,"image","","coinid","")).put(object("idx",2,"image","","coinid","")));LocalStore.save(a,new JSONArray().put(first).put(other));second.set(other.toString());
                set(a,"imagesLauncher",new androidx.activity.result.ActivityResultLauncher<String>(){
                    @Override public void launch(String input,androidx.core.app.ActivityOptionsCompat options){assertEquals("image/*",input);}
                    @Override public void unregister(){}
                    @Override public androidx.activity.result.contract.ActivityResultContract<String,?> getContract(){return new androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents();}
                });
                MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];set(m,"progressRowId",FIRST);mint(a,"PROGRESS");call(m,"pickProgressImages",new Class<?>[]{JSONObject.class,int.class},first,1);set(m,"progressRowId",SECOND);m.refresh();((java.util.function.Consumer<List<Uri>>)get(a,"imagesConsumer")).accept(Collections.singletonList(Uri.fromFile(fixture)));
            });
            boolean saved=false;for(int i=0;i<100;i++){settle();JSONObject row=LocalStore.findById(context,FIRST);if(!row.getJSONArray("items").getJSONObject(0).getString("image").isEmpty()){saved=true;break;}android.os.SystemClock.sleep(50);}assertTrue("Compression callback completed",saved);
            s.onActivity(a->{assertEquals(second.get(),LocalStore.findById(a,SECOND).toString());assertEquals("STAMP",LocalStore.findById(a,FIRST).optString("phase"));MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];assertEquals(SECOND,((Long)get(m,"progressRowId")).longValue());assertEquals(Collections.singletonList("block"),n.get().requests);clearRows();n.get().onDestroy();assertFalse(MintDriver.isRunning());});
        }finally{assertTrue(fixture.delete());}
    }
    @Test public void repostConfirmationCannotResetNewlySelectedCollection()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            settle();s.onActivity(a->{
                PendingNode n=node(a);JSONObject first=object("id",FIRST,"name","PUBLIC FIRST","phase","CREATE","tokenid","","posted",1,"createstuck",1,"size",2,"mode","url","items",new JSONArray());JSONObject other=new JSONObject();try{other=new JSONObject(first.toString()).put("id",SECOND).put("name","PUBLIC SECOND");}catch(Exception e){throw new AssertionError(e);}LocalStore.save(a,new JSONArray().put(first).put(other));String unchanged=other.toString();
                MintView m=(MintView)((BaseView[])get(a,"views"))[MainActivity.TAB_MINT];set(m,"progressRowId",FIRST);mint(a,"PROGRESS");exact(m.getRoot(),Language.msg(l10n_nft_mint_repost_create)).performClick();set(m,"progressRowId",SECOND);m.refresh();exact(dialog(a).getWindow().getDecorView(),Language.msg(l10n_nft_mint_repost)).performClick();
                assertEquals(0,LocalStore.findById(a,FIRST).optInt("posted"));assertEquals(unchanged,LocalStore.findById(a,SECOND).toString());assertEquals(Collections.singletonList("block"),n.requests);clearRows();n.onDestroy();assertFalse(MintDriver.isRunning());
            });
        }
    }

}
