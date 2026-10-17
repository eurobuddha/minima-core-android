package com.eurobuddha.wallet;

import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.minima.objects.Address;
import org.minima.objects.base.MiniData;
import org.minima.utils.json.JSONObject;
import org.minima.utils.json.JSONArray;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;

/** Synthetic public NFT metadata only; no seed, transaction factory, node, image network or signing. */
@RunWith(AndroidJUnit4.class)
public class WalletNftLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String[] codes={"zh-Hans","ru","uk","en"};
    private static final String NAME="Art 中文 / Русский / Українська";
    private static final String DESCRIPTION="Literal artist description — 12.340000";
    private static final String TOKEN="0x12345678";
    private static final Address ADDRESS=new Address("RETURN TRUE");
    private static final Address RECIPIENT=new Address("RETURN FALSE");
    private static final String[] IDS={"0xaa","0xbb","0xcc","0xdd"};
    private static final KeyUses READ_ONLY=new KeyUses(){
        public int currentUses(int i){return 7;}
        public int reserveNextUse(int i){throw new AssertionError("No signing");}
        public void recordExternalUses(int i,int n){throw new AssertionError("No counter writes");}
        public Map<Integer,Integer> snapshotAllUses(){return java.util.Collections.singletonMap(0,7);}
    };
    private boolean disposable;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(WalletSession.get(context).vault().exists());assertFalse(WalletSession.get(context).work().busy());
            AppLock.disable(context);Language.select(context,"en");
            for(String id:IDS)assertFalse(pending().containsKey(id));
            disposable=true;
        });
    }
    @After public void clean(){if(!disposable)return;InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(String id:IDS)pending().remove(id);});}
    @SuppressWarnings("unchecked") private static Map<String,Long> pending(){return (Map<String,Long>)get(null,NftView.class,"PENDING");}
    private static Object get(Object target,Class<?> type,String name){
        try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}
    }
    private static void set(Object target,String name,Object value){
        try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args){
        try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){throw new AssertionError(e);}
    }
    private static Class<?> groupType(){try{return Class.forName("com.eurobuddha.wallet.NftView$Group");}catch(Exception e){throw new AssertionError(e);}}
    private static <T extends View> List<T> views(View root,Class<T> type){
        List<T> result=new ArrayList<>();if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i),type));return result;
    }
    private void settle(){android.os.SystemClock.sleep(350);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private void screenshot(String name)throws Exception{
        java.io.File folder=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(folder.isDirectory()||folder.mkdirs());
        android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
    }
    private static final class Fixture {NftView view;Object group;List<JSONObject> coins;}
    @SuppressWarnings("unchecked") private Fixture fixture(MainActivity a,boolean extras){
        set(a,"mKeyUses",READ_ONLY);
        set(a,"mUiSession",(java.util.function.BooleanSupplier)()->true);
        set(a,"mBook",new AddressBook(a,new WalletCore(new MiniData("0x00"),READ_ONLY)){
            @Override public synchronized Integer keyIndexFor(String address){return ADDRESS.getAddressData().to0xString().equals(address)?0:null;}
        });
        JSONObject name=new JSONObject();name.put("name",NAME);name.put("description",DESCRIPTION);
        JSONObject token=new JSONObject();token.put("name",name);token.put("decimals","0");
        token.put("script","LET s=PREVSTATE(0) IF s EQ 0 AND SIGNEDBY(0xEE) THEN RETURN TRUE ENDIF RETURN SAMESTATE(0 1) AND VERIFYOUT(@INPUT GETOUTADDR(@INPUT) @AMOUNT @TOKENID TRUE)");
        JSONArray coins=new JSONArray();
        for(int i=0;i<(extras?4:2);i++){
            JSONObject coin=new JSONObject();coin.put("coinid",IDS[i]);coin.put("tokenid",TOKEN);coin.put("address",ADDRESS.getAddressData().to0xString());
            coin.put("amount","1.000000");coin.put("created","123456");coin.put("token",token);
            JSONObject state=new JSONObject();state.put("0",i==3?"0":String.valueOf(i+7));state.put("2","literal state 中文");coin.put("state",state);coins.add(coin);
        }
        set(a,"mLastCoins",coins);if(extras)pending().put(IDS[2],System.currentTimeMillis());
        Fixture f=new Fixture();f.view=new NftView(a);f.coins=new ArrayList<>();for(Object c:coins)f.coins.add((JSONObject)c);
        try{java.lang.reflect.Constructor<?> constructor=groupType().getDeclaredConstructor();constructor.setAccessible(true);f.group=constructor.newInstance();}
        catch(Exception e){throw new AssertionError(e);}
        set(f.group,"tokenid",TOKEN);set(f.group,"meta",Nft.meta(token));
        ((List<JSONObject>)get(f.group,groupType(),"coins")).addAll(f.coins);
        a.setContentView(f.view.getRoot());assertFalse(a.vault().isOpen());assertNull(a.factory());assertNull(a.node());return f;
    }
    private void assertReviewText(String value,String recipient,boolean collection){
        assertTrue(value.contains(NAME));assertTrue(value.contains(recipient));
        assertTrue(value.contains(Language.text(context,l10n_wallet_key_usage,0,7,262144)));
        assertTrue(value.contains(Language.text(context,l10n_wallet_irreversible)));
        if(collection){
            assertTrue(value.contains("#7, #8"));
            assertTrue(value.contains(Language.text(context,l10n_nft_skipping_pending,1)));
            assertTrue(value.contains(Language.text(context,l10n_nft_skipping_unstamped,1)));
            assertTrue(value.contains(Language.text(context,l10n_nft_separate_transactions)));
        }
    }
    @Test public void galleryViewerAndDetailsKeepArtworkSelectionAndMetadata()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<Fixture> fixture=new AtomicReference<>();AtomicReference<Dialog> browser=new AtomicReference<>();
            scenario.onActivity(a->{Fixture f=fixture(a,false);fixture.set(f);ImageView image=views(f.view.getRoot(),ImageView.class).get(0);assertTrue(((View)image.getParent()).performClick());browser.set((Dialog)get(f.view,NftView.class,"mBrowser"));});settle();
            AtomicReference<Dialog> viewer=new AtomicReference<>();AtomicReference<ImageView> image=new AtomicReference<>();AtomicReference<android.graphics.drawable.Drawable> artwork=new AtomicReference<>();
            scenario.onActivity(a->{
                ImageView selected=null;for(ImageView v:views(browser.get().getWindow().getDecorView(),ImageView.class))if((NAME+" #8").equals(String.valueOf(v.getContentDescription())))selected=v;
                assertNotNull(selected);assertTrue(((View)((View)selected.getParent()).getParent()).performClick());
                viewer.set((Dialog)get(fixture.get().view,NftView.class,"mViewer"));image.set(views(viewer.get().getWindow().getDecorView(),ZoomImageView.class).get(0));artwork.set(image.get().getDrawable());
            });settle();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                assertSame(viewer.get(),get(fixture.get().view,NftView.class,"mViewer"));assertSame(artwork.get(),image.get().getDrawable());
                List<String> text=new ArrayList<>();for(TextView v:views(viewer.get().getWindow().getDecorView(),TextView.class))text.add(v.getText().toString());
                assertTrue(text.contains(NAME+"  #8"));assertTrue(text.contains("2 / 2"));assertTrue(text.contains(Language.text(a,l10n_nft_details)));
                ImageView cover=views(fixture.get().view.getRoot(),ImageView.class).get(0);
                assertEquals(NAME+", "+Language.quantity(a,com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items,2,2),cover.getContentDescription().toString());
            });}
            onView(withText(Language.text(context,l10n_nft_details))).inRoot(isDialog()).perform(click());settle();
            AtomicReference<View> root=new AtomicReference<>();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_nft_details))).inRoot(isDialog()).check((view,error)->{
                    if(error!=null)throw error;if(root.get()==null)root.set(view.getRootView());else assertSame(root.get(),view.getRootView());
                    boolean found=false;for(TextView t:views(view.getRootView(),TextView.class))if(t.getText().toString().contains(TOKEN)){
                        String value=t.getText().toString();found=true;assertTrue(value.contains(NAME+"  #8"));assertTrue(value.contains(DESCRIPTION));
                        assertTrue(value.contains(IDS[1]));assertTrue(value.contains("1.000000"));assertTrue(value.contains("literal state 中文"));
                        assertTrue(value.contains(Language.text(context,l10n_nft_state_port,"2")));assertTrue(value.contains(Language.text(context,l10n_nft_held_address)));
                    }assertTrue(found);
                });screenshot("wallet-nft-details-"+code);
            }
            onView(withText(Language.text(context,l10n_nft_copy_coin_id))).inRoot(isDialog()).perform(click());settle();
            scenario.onActivity(a->{ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(IDS[1],clipboard.getPrimaryClip().getItemAt(0).getText().toString());viewer.get().dismiss();browser.get().dismiss();assertFalse(a.vault().exists());});
        }
    }
    @Test public void itemRecipientDraftSurvivesEveryLanguage()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{Fixture f=fixture(a,false);invoke(f.view,"promptSendItem",new Class<?>[]{groupType(),JSONObject.class},f.group,f.coins.get(0));});settle();
            AtomicReference<EditText> original=new AtomicReference<>();String recipient=RECIPIENT.getMinimaAddress();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check((view,error)->{if(error!=null)throw error;original.set((EditText)view);original.get().setText(recipient);original.get().setSelection(5);});
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_nft_send_named,NAME+" #7"))).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check((view,error)->{if(error!=null)throw error;EditText e=(EditText)view;assertSame(original.get(),e);assertEquals(recipient,e.getText().toString());assertEquals(5,e.getSelectionStart());assertEquals(Language.text(context,l10n_wallet_recipient),e.getHint().toString());});
                onView(withText(Language.text(context,l10n_nft_review))).inRoot(isDialog()).check(matches(isDisplayed()));screenshot("wallet-nft-recipient-"+code);
            }pressBack();scenario.onActivity(a->{assertFalse(a.vault().exists());assertNull(a.factory());});
        }
    }
    @Test public void collectionReviewRetainsRecipientSkippedItemsAndKeyUse()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{Fixture f=fixture(a,true);invoke(f.view,"promptSendCollection",new Class<?>[]{groupType()},f.group);});settle();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check((view,error)->{if(error!=null)throw error;((EditText)view).setText(ADDRESS.getMinimaAddress());});
            onView(withText(Language.text(context,l10n_nft_review))).inRoot(isDialog()).perform(click());settle();
            AtomicReference<View> original=new AtomicReference<>();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withId(android.R.id.message)).inRoot(isDialog()).check((view,error)->{if(error!=null)throw error;if(original.get()==null)original.set(view);else assertSame(original.get(),view);String value=((TextView)view).getText().toString();assertReviewText(value,ADDRESS.getMinimaAddress(),true);assertTrue(value.contains(Language.text(context,l10n_nft_self_send,0)));assertTrue(value.contains(Language.quantity(context,com.eurobuddha.minimacore.i18n.R.plurals.l10n_nft_items,2,2)));});
                onView(withText(Language.text(context,l10n_nft_sign_all))).inRoot(isDialog()).check(matches(isDisplayed()));screenshot("wallet-nft-review-"+code);
                scenario.onActivity(a->{assertFalse(a.vault().isOpen());assertFalse(a.vault().isKeyUsesTrusted());assertNull(a.factory());});
            }pressBack();
        }
    }
    @Test public void retryReviewKeepsPendingAndRecipientAcrossLanguages()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                Fixture f=fixture(a,false);pending().put(IDS[0],1L);
                f.view.refresh();assertEquals(Long.valueOf(1),pending().get(IDS[0]));
                invoke(f.view,"reviewCollection",new Class<?>[]{groupType(),List.class,int.class,int.class,String.class,boolean.class,Runnable.class},
                        f.group,f.coins,0,0,RECIPIENT.getMinimaAddress(),true,(Runnable)()->{throw new AssertionError("Must not approve retry");});
            });settle();
            AtomicReference<View> original=new AtomicReference<>();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withId(android.R.id.message)).inRoot(isDialog()).check((view,error)->{
                    if(error!=null)throw error;if(original.get()==null)original.set(view);else assertSame(original.get(),view);
                    String value=((TextView)view).getText().toString();assertReviewText(value,RECIPIENT.getMinimaAddress(),false);
                    assertTrue(value.contains(Language.text(context,l10n_nft_retry_warning)));
                });
                onView(withText(Language.text(context,l10n_nft_sign_all))).inRoot(isDialog()).check(matches(isDisplayed()));
                scenario.onActivity(a->{assertEquals(Long.valueOf(1),pending().get(IDS[0]));assertNull(a.factory());assertNull(a.node());});
            }
            onView(withText(Language.text(context,l10n_common_cancel))).inRoot(isDialog()).perform(click());
            scenario.onActivity(a->{assertEquals(Long.valueOf(1),pending().get(IDS[0]));assertFalse(a.vault().exists());});
        }
    }
    @Test public void agedPendingItemCanOpenReviewFromTileAndViewer()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<Fixture> fixture=new AtomicReference<>();
            scenario.onActivity(a->{
                Fixture f=fixture(a,false);fixture.set(f);pending().put(IDS[0],1L);f.view.refresh();
                View tile=(View)invoke(f.view,"itemTile",new Class<?>[]{groupType(),JSONObject.class},f.group,f.coins.get(0));
                boolean found=false;for(TextView t:views(tile,TextView.class))if(Language.text(a,l10n_nft_review).equals(t.getText().toString())){
                    assertTrue(t.hasOnClickListeners());assertTrue(t.performClick());found=true;
                }assertTrue(found);
            });settle();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(isDisplayed()));pressBack();
            scenario.onActivity(a->{Fixture f=fixture.get();invoke(f.view,"openViewer",new Class<?>[]{groupType(),int.class},f.group,0);});settle();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withText(Language.text(context,l10n_nft_review))).inRoot(isDialog()).check(matches(isEnabled()));
            }
            onView(withText(Language.text(context,l10n_nft_review))).inRoot(isDialog()).perform(click());settle();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(isDisplayed()));pressBack();
            scenario.onActivity(a->{assertEquals(Long.valueOf(1),pending().get(IDS[0]));assertNull(a.factory());assertNull(a.node());
                ((Dialog)get(fixture.get().view,NftView.class,"mViewer")).dismiss();
            });
        }
    }
    @Test public void sharedSingleItemReviewRetainsDynamicTextAndLiteralRecipient()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{fixture(a,false);a.confirmSignAndPublish(Language.join(NAME,"\n",Language.msg(l10n_nft_state_replayed),"\n",RECIPIENT.getMinimaAddress()),0,()->{throw new AssertionError("Must not sign");});});settle();
            for(String code:codes){scenario.onActivity(a->Language.select(a,code));settle();
                onView(withId(android.R.id.message)).inRoot(isDialog()).check((view,error)->{if(error!=null)throw error;String value=((TextView)view).getText().toString();assertReviewText(value,RECIPIENT.getMinimaAddress(),false);assertTrue(value.contains(Language.text(context,l10n_nft_state_replayed)));});
                onView(withText(Language.text(context,l10n_wallet_sign_broadcast))).inRoot(isDialog()).check(matches(isDisplayed()));
            }pressBack();
        }
    }
}
