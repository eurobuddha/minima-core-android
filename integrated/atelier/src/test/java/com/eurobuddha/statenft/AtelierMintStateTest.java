package com.eurobuddha.statenft;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Delayed fake-node/network replies exercise the real mint handlers. No blockchain writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class AtelierMintStateTest {
    private MainActivity activity;
    private LinearLayout body;
    private NodeApi node;
    private final List<String> commands=new ArrayList<>();
    private final List<NodeApi.Cb> callbacks=new ArrayList<>();
    private static final String PK="0x"+"ab".repeat(32);
    private Field field(String n)throws Exception {Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception {field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception {Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    private void render(String n)throws Exception {call(n,new Class<?>[]{});}
    private void mint(String kind)throws Exception {call(kind.equals("nft")?"mintNft":"mintToken",new Class<?>[]{TextView.class},new TextView(activity));}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atelier_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);set("body",body);
        for(String name:new String[]{"appbarBox","navBox"})set(name,new LinearLayout(activity));
        set("content",new ScrollView(activity));node=mock(NodeApi.class);set("node",node);
        doAnswer(c->{commands.add(c.getArgument(0));callbacks.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any(NodeApi.Cb.class));
        for(String kind:new String[]{"nft","token","collection"})LocalStore.clearDraft(activity,kind);
        // Load Android preferences before intercepting only the verifier worker thread.
        HostingStore.list(activity);
        // Inflate native input drawables before Thread construction is intercepted (ImageIO starts a disposer).
        new EditText(activity);
    }
    @After public void cleanup(){Language.select(activity,"en");}
    @SuppressWarnings("unchecked") private List<String[]> pairs(String n)throws Exception{return (List<String[]>)field(n).get(activity);}
    private void draft(String kind,boolean hosted)throws Exception {
        if(kind.equals("nft")){
            set("nftName","Original NFT");set("nftDesc","Original description");set("nftOwner","Original owner");set("nftEditions","2");set("nftEmbed",false);set("nftUrl",hosted?"https://example.invalid/original.png":"ipfs://original.png");set("nftWeb","https://example.invalid/proof");
            pairs("nftTraits").add(new String[]{"Mood","Original"});render("renderCreateNft");
        }else{
            set("tokName","Original token");set("tokDesc","Original description");set("tokTicker","OLD");set("tokSupply","1234");set("tokDecimals","2");set("tokUrl",hosted?"https://example.invalid/original.png":"ipfs://original.png");
            pairs("tokPairs").add(new String[]{"team","Original"});render("renderCreateToken");
        }
    }
    private JSONObject address(boolean status)throws Exception{return new JSONObject().put("status",status).put("response",new JSONObject().put("publickey",PK).put("address","0xaddress"));}
    private JSONObject metadata(String cmd)throws Exception{return new JSONObject(cmd.substring("tokencreate name:".length(),cmd.indexOf(" amount:")));}
    private static TextView find(View root,String text){if(root instanceof TextView&&text.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    @Test public void editsWhileFetchingKeyDoNotChangeCommandOrGetErasedOnSuccess()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            for(String kind:new String[]{"nft","token"}){
                commands.clear();callbacks.clear();draft(kind,false);mint(kind);assertEquals(List.of("getaddress"),commands);
                String prefix=kind.equals("nft")?"nft":"tok";
                set(prefix+"Name","New draft");set(prefix+"Desc","New description");set(prefix+"Url","https://changed.invalid/image");
                if(kind.equals("nft")){set("nftOwner","New owner");set("nftExternal","https://bad.invalid/;quit");set("nftWeb","https://changed.invalid/proof");pairs("nftTraits").get(0)[1]="Changed";set("traitTypeDraft","Next trait");}
                else{set("tokTicker","NEW");pairs("tokPairs").get(0)[1]="Changed";set("pairKeyDraft","Next key");}
                Language.select(activity,"uk");callbacks.get(0).onResult(address(true));assertEquals(2,commands.size());
                JSONObject meta=metadata(commands.get(1));assertEquals("Original description",meta.getString("description"));assertEquals("ipfs://original.png",meta.getString("url"));assertFalse(commands.get(1).contains("changed.invalid"));assertTrue(commands.get(1).endsWith(" signtoken:"+PK));
                if(kind.equals("nft")){assertEquals("Original owner",meta.getString("owner"));assertEquals("Original",meta.getJSONArray("attributes").getJSONObject(0).getString("value"));assertTrue(commands.get(1).contains("webvalidate:https://example.invalid/proof"));}else{assertEquals("OLD",meta.getString("ticker"));assertEquals("Original",meta.getString("team"));}
                callbacks.get(1).onResult(new JSONObject().put("status",true));assertEquals("New draft",field(prefix+"Name").get(activity));assertEquals("New description",field(prefix+"Desc").get(activity));assertEquals(false,field(prefix+"Busy").get(activity));
            }
        }
    }
    @Test public void successfulUnchangedDraftClearsOnlyItsOwnDraftAndDoesNotNavigateFromOtherScreen()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            for(String kind:new String[]{"nft","token"}){
                commands.clear();callbacks.clear();draft(kind,false);mint(kind);callbacks.get(0).onResult(address(true));
                render(kind.equals("nft")?"renderCreateToken":"renderCreateNft");Object otherScreen=field("screen").get(activity);
                callbacks.get(1).onResult(new JSONObject().put("status",true));assertSame(otherScreen,field("screen").get(activity));assertEquals("",field(kind.equals("nft")?"nftName":"tokName").get(activity));
            }
        }
    }
    @Test public void failedKeyRepliesNeverSendMintAndAllowRetry()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            for(String kind:new String[]{"nft","token"}){
                commands.clear();callbacks.clear();draft(kind,false);mint(kind);callbacks.get(0).onResult(address(false));assertEquals(List.of("getaddress"),commands);assertEquals(false,field(kind.equals("nft")?"nftBusy":"tokBusy").get(activity));mint(kind);assertEquals(2,commands.size());callbacks.get(1).onError("offline");assertEquals(false,field(kind.equals("nft")?"nftBusy":"tokBusy").get(activity));
            }
        }
    }
    @Test public void editedHostedDraftCannotUsePreviousVerificationAndRetryChecksNewUrl()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            for(String kind:new String[]{"nft","token"}){
                commands.clear();callbacks.clear();draft(kind,true);mint(kind);idle();assertNotNull(job.get());assertTrue(commands.isEmpty());
                String field=kind.equals("nft")?"nftUrl":"tokUrl";set(field,"https://example.invalid/new.png");job.get().run();idle();assertTrue(commands.isEmpty());assertEquals(Language.msg(l10n_atelier_mint_draft_changed).toString(),ShadowToast.getTextOfLatestToast());assertEquals(false,field("hostGatePassed").get(activity));
                mint(kind);idle();job.get().run();idle();assertEquals(List.of("getaddress"),commands);hosting.verify(()->Hosting.verifyUrl(eq("https://example.invalid/new.png"),isNull(),eq(0L),any(Hosting.VerifyTick.class)),atLeastOnce());
                callbacks.get(0).onError("test finished");
            }
        }
    }
    @Test public void stopAfterLastUrlResponseStillPreventsMint()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            draft("nft",true);mint("nft");idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();job.get().run();
            // Network has completed, but completion has not run on the main looper yet.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle();assertTrue(commands.isEmpty());assertFalse((Boolean)field("verifyGateRunning").get(activity));assertEquals(Language.msg(l10n_atelier_mint_stopped).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void leavingAndReturningToSameFormInvalidatesPendingVerification()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            draft("nft",true);mint("nft");idle();render("renderCreateToken");render("renderCreateNft");job.get().run();idle();assertTrue(commands.isEmpty());assertEquals(Language.msg(l10n_atelier_mint_draft_changed).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void hiddenVerificationKeepsWorkingAndDialogControlsSwitchLanguages()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            draft("token",true);mint("token");idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);assertNotNull(find(dialog.getWindow().getDecorView(),Language.msg(l10n_atelier_mint_verify_title).toString()));assertEquals(Language.msg(l10n_atelier_mint_stop).toString(),dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());}
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();job.get().run();idle();assertEquals(List.of("getaddress"),commands);assertEquals(false,field("hostGatePassed").get(activity));
        }
    }
    private List<EditText> inputs(View root){List<EditText> out=new ArrayList<>();if(root instanceof EditText)out.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)out.addAll(inputs(((ViewGroup)root).getChildAt(i)));return out;}
    @Test public void collectionRejectsChangedDraftAndDuplicateClicksWhileFetchingKey()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            set("createName","Original collection");set("createSize","2");set("createMode","url");set("createBase","https://example.invalid/plates/");set("createUploadedBase","https://example.invalid/plates/");render("renderCreateCollection");
            // Exercise the post-verification key request. The real gate is covered above.
            set("hostGatePassed",true);find(body,"MINT COLLECTION").performClick();find(body,"MINT COLLECTION").performClick();assertEquals(List.of("getaddress"),commands);
            inputs(body).get(0).setText("New collection");callbacks.get(0).onResult(address(true));assertEquals(List.of("getaddress"),commands);assertEquals("New collection",field("createName").get(activity));assertEquals(false,field("collectionBusy").get(activity));assertEquals(Language.msg(l10n_atelier_mint_draft_changed).toString(),ShadowToast.getTextOfLatestToast());
        }
    }
    @Test public void collectionConfirmationDoesNotAuthorizeAnEditedBaseUrl()throws Exception {
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class)){
            set("createName","Collection");set("createSize","2");set("createBase","https://example.invalid/original/");render("renderCreateCollection");
            find(body,"MINT COLLECTION").performClick();idle();AlertDialog confirmation=(AlertDialog)ShadowDialog.getLatestDialog();
            ((EditText)find(body,"https://example.invalid/original/")).setText("https://example.invalid/different/");
            confirmation.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();idle();
            assertTrue(commands.isEmpty());assertEquals(false,field("verifyGateRunning").get(activity));assertNull(field("confirmedCollectionDraft").get(activity));assertEquals("https://example.invalid/different/",field("createBase").get(activity));
        }
    }
    @Test public void liveVerificationProgressRetainsUrlAndChangesLanguage()throws Exception {
        AtomicReference<Runnable> job=new AtomicReference<>();
        try(MockedStatic<ImageLoader> images=mockStatic(ImageLoader.class);MockedStatic<Hosting> hosting=mockStatic(Hosting.class);MockedConstruction<Thread> threads=mockConstruction(Thread.class,(m,c)->job.set((Runnable)c.arguments().get(0)))){
            draft("nft",true);mint("nft");idle();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
            hosting.when(()->Hosting.verifyUrl(anyString(),isNull(),anyLong(),any(Hosting.VerifyTick.class))).thenAnswer(c->{
                Hosting.VerifyTick tick=c.getArgument(3);assertTrue(tick.onTick(c.getArgument(0),2,0));idle();
                for(String code:new String[]{"zh-Hans","ru","uk","en"}){Language.select(activity,code);String expected=Language.msg(l10n_atelier_mint_progress,1,1,2,0,0)+"\n\nhttps://example.invalid/original.png";assertNotNull(find(dialog.getWindow().getDecorView(),expected));}
                return null;
            });
            job.get().run();idle();assertEquals(List.of("getaddress"),commands);
        }
    }

}
