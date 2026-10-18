package com.eurobuddha.salon;

import android.content.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
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

/** Native retained browsing views. Fake executors/cached profiles; no network or node writes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonSocialLanguageTest {
    private MainActivity activity;private LinearLayout body,appbar;private NodeApi node;
    private ExecutorService worker;private final List<Runnable> jobs=new ArrayList<>();
    private MockedStatic<ImageLoader> images;private MockedStatic<WebValidate> verification;
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private Field field(String n)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f;}
    private void set(String n,Object v)throws Exception{field(n).set(activity,v);}
    private Object call(String n,Class<?>[] types,Object...args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(activity,args);}
    @SuppressWarnings({"unchecked","rawtypes"})private void screen(String name)throws Exception{set("screen",Enum.valueOf((Class)field("screen").getType(),name));}
    private void render()throws Exception{call("render",new Class<?>[]{});}
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private String upper(int id){return Language.upper(Language.msg(id)).toString();}
    @SuppressWarnings("unchecked")private Map<String,Object[]> cache()throws Exception{return(Map<String,Object[]>)field("mProfileCache").get(activity);}
    private void cache(String url,JSONObject profile)throws Exception{cache().put(url,new Object[]{profile,System.currentTimeMillis()});}
    @Before public void setup()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_salon_Theme_StateNFT);
        body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);appbar=new LinearLayout(activity);appbar.setOrientation(LinearLayout.VERTICAL);set("body",body);set("appbar",appbar);set("navRow",new LinearLayout(activity));
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);doAnswer(c->{jobs.add(c.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));node=mock(NodeApi.class);set("node",node);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"handle","owner");SalonStore.put(activity,"tokenid","0xowner");SalonStore.put(activity,"profileUrl","https://example.invalid/owner/profile.json");
        images=mockStatic(ImageLoader.class);verification=mockStatic(WebValidate.class);
    }
    @After public void cleanup(){images.close();verification.close();worker.shutdownNow();Language.select(activity,"en");}
    private static TextView find(View root,String value){if(root instanceof TextView&&value.equals(((TextView)root).getText().toString()))return(TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView t=find(((ViewGroup)root).getChildAt(i),value);if(t!=null)return t;}return null;}
    private static void fits(View root){if(root instanceof TextView&&!(root instanceof EditText)){TextView t=(TextView)root;if(t.getLayout()!=null)assertTrue(t.getText()+" w="+t.getWidth()+" h="+t.getHeight(),t.getLayout().getHeight()<=t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom());}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)fits(((ViewGroup)root).getChildAt(i));}
    private void layout(){for(int w:new int[]{320,480,900})for(View root:new View[]{body,appbar}){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));root.layout(0,0,w,root.getMeasuredHeight());fits(root);}}
    private JSONObject profile(String who)throws Exception{return new JSONObject().put("name",who+" 中文 Русский Українська").put("handle",who).put("tokenid","0x"+who).put("bio","Original bio").put("about","Original about").put("updated",System.currentTimeMillis()/1000-3600).put("links",new JSONArray().put(new JSONObject().put("label","My link").put("url","https://example.invalid/дані?q=中文"))).put("posts",new JSONArray().put(new JSONObject().put("text","Original post 中文 Русский Українська").put("ts",System.currentTimeMillis()/1000-3600)));}
    @Test public void profileDisplayRetainsContentAndUsesLocalizedDatesCountsAndActions()throws Exception{
        JSONObject p=profile("owner");String before=p.toString(),stored=SalonStore.me(activity).toString();call("renderProfilePage",new Class<?>[]{JSONObject.class,boolean.class},p,true);
        View first=body.getChildAt(0);
        for(String code:CODES){Language.select(activity,code);layout();assertSame(first,body.getChildAt(0));assertEquals(before,p.toString());assertEquals(stored,SalonStore.me(activity).toString());assertNotNull(find(body,p.getString("name")));assertNotNull(find(body,"Original bio"));assertNotNull(find(body,"Original about"));assertNotNull(find(body,"Original post 中文 Русский Українська"));assertNotNull(find(body,upper(l10n_salon_social_edit_page)));assertNotNull(find(body,upper(l10n_salon_social_share_page)));assertNotNull(find(body,upper(l10n_salon_social_posts)));assertNotNull(find(body,Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_post_count,1)).toString()));assertNotNull(find(body,Language.msg(l10n_salon_social_updated,Language.since(p.getLong("updated")*1000)).toString()));}
        verify(node,never()).cmd(anyString(),any(NodeApi.Cb.class));
    }
    @Test public void emptyAndFailedFeedsTranslateAndMalformedFollowsFinishLoading()throws Exception{
        screen("FEED");render();for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.msg(l10n_salon_social_feed_empty).toString()));assertNotNull(find(body,upper(l10n_salon_social_discover_action)));}
        SalonStore.setArr(activity,"follows",new JSONArray().put(JSONObject.NULL));render();assertNotNull(find(body,Language.msg(l10n_salon_social_no_posts).toString()));assertTrue(jobs.isEmpty());
        SalonStore.setArr(activity,"follows",new JSONArray());SalonStore.follow(activity,"0xfailed","fail","invalid-ref");render();assertNotNull(find(body,Language.msg(l10n_salon_social_pulling,1).toString()));jobs.remove(0).run();idle();
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.msg(l10n_salon_social_unreachable,1).toString()));assertNotNull(find(body,upper(l10n_pools_lp_try_again)));}
    }
    @Test public void feedNeverWritesAttributionIntoCachedPublicProfile()throws Exception{
        JSONObject p=profile("author");String before=p.toString();cache("https://example.invalid/a",p);SalonStore.follow(activity,"0xauthor","author","https://example.invalid/a");screen("FEED");render();jobs.remove(0).run();idle();assertEquals(before,p.toString());
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,"@author"));assertNotNull(find(body,"Original post 中文 Русский Українська"));assertNotNull(find(body,Language.since(p.getJSONArray("posts").getJSONObject(0).getLong("ts")*1000).toString()));}
        render();Runnable late=jobs.remove(0);screen("DISCOVER");render();View discover=body.getChildAt(0);late.run();idle();assertSame(discover,body.getChildAt(0));assertNull(find(body,"@author"));assertEquals(before,p.toString());
    }
    @Test public void discoverRefreshClearsCachesAndRequeriesRegistry()throws Exception{
        screen("DISCOVER");render();for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.msg(l10n_salon_setup_waiting_node).toString()));assertNotNull(find(body,Language.msg(l10n_salon_social_discover_help,SalonRegistry.SALON_ADDRESS).toString()));}
        try(MockedStatic<SalonRegistry> registry=mockStatic(SalonRegistry.class)){
            set("nodeUp",true);set("mRegistryCache",new ArrayList<SalonRegistry.Entry>());set("mRegistryCacheTs",System.currentTimeMillis());cache("https://example.invalid/a",profile("a"));render();find(body,upper(l10n_salon_social_refresh)).performClick();assertNull(field("mRegistryCache").get(activity));assertTrue(cache().isEmpty());registry.verify(()->SalonRegistry.list(eq(node),any(SalonRegistry.Listed.class)));
        }
    }
    @Test public void profileResponsesCannotOverwriteNewerSelectionOrRetry()throws Exception{
        SalonRegistry.Entry a=new SalonRegistry.Entry("0xa","https://example.invalid/a","a"),b=new SalonRegistry.Entry("0xb","https://example.invalid/b","b");JSONObject pa=profile("a"),pb=profile("b");cache(a.url,pa);cache(b.url,pb);
        call("openProfile",new Class<?>[]{SalonRegistry.Entry.class},a);Runnable first=jobs.remove(0);call("openProfile",new Class<?>[]{SalonRegistry.Entry.class},b);Runnable second=jobs.remove(0);second.run();idle();first.run();idle();assertSame(pb,field("viewProfile").get(activity));assertNotNull(find(body,pb.getString("name")));assertNull(find(body,pa.getString("name")));
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,upper(l10n_salon_social_follow)));}
        find(body,upper(l10n_salon_social_follow)).performClick();assertTrue(SalonStore.isFollowing(activity,b.tokenid));find(body,upper(l10n_salon_social_unfollow)).performClick();assertFalse(SalonStore.isFollowing(activity,b.tokenid));
        call("openProfile",new Class<?>[]{SalonRegistry.Entry.class},b);first=jobs.remove(0);call("openProfile",new Class<?>[]{SalonRegistry.Entry.class},b);second=jobs.remove(0);JSONObject newer=profile("newer");cache(b.url,newer);second.run();idle();cache(b.url,pb);first.run();idle();assertSame(newer,field("viewProfile").get(activity));
    }
    @Test public void authorLookupDoesNotNavigateAfterLeavingOrAfterANewerTap()throws Exception{
        List<SalonRegistry.Listed> replies=new ArrayList<>();
        try(MockedStatic<SalonRegistry> registry=mockStatic(SalonRegistry.class)){
            registry.when(()->SalonRegistry.list(eq(node),any(SalonRegistry.Listed.class))).thenAnswer(c->{replies.add(c.getArgument(1));return null;});screen("FEED");render();call("openTokenProfile",new Class<?>[]{String.class},"0xa");call("openTokenProfile",new Class<?>[]{String.class},"0xb");
            SalonRegistry.Entry a=new SalonRegistry.Entry("0xa","invalid-a","a"),b=new SalonRegistry.Entry("0xb","invalid-b","b");replies.get(0).done(Arrays.asList(a));idle();assertEquals("FEED",field("screen").get(activity).toString());replies.get(1).done(Arrays.asList(b));idle();assertSame(b,field("viewEntry").get(activity));
            set("mRegistryCache",null);call("openTokenProfile",new Class<?>[]{String.class},"0xa");screen("DISCOVER");render();replies.get(2).done(Arrays.asList(a));idle();assertEquals("DISCOVER",field("screen").get(activity).toString());assertSame(b,field("viewEntry").get(activity));
        }
    }
    @Test public void remoteProfileCannotBorrowAnotherTokensWebVerification()throws Exception{
        JSONObject p=profile("remote").put("tokenid","0xborrowed").put("webvalidate","https://example.invalid/proof");
        set("viewEntry",new SalonRegistry.Entry("0xdiscovered","https://example.invalid/remote","remote"));
        verification.when(()->WebValidate.status("0xborrowed", "https://example.invalid/proof")).thenReturn(true);
        call("renderProfilePage",new Class<?>[]{JSONObject.class,boolean.class},p,false);
        verification.verify(()->WebValidate.status("0xborrowed", "https://example.invalid/proof"),never());
        verification.verify(()->WebValidate.ensure(eq(activity),any(View.class),eq("0xdiscovered"),eq("https://example.invalid/proof"),any(Runnable.class)),times(2));
        assertNull(find(body,Language.msg(l10n_salon_social_verified_host,"example.invalid").toString()));
        assertEquals("0xborrowed",p.getString("tokenid"));
    }
    @Test public void windowedPostsEmptyProfilesAndCounterPluralsFollowLanguage()throws Exception{
        JSONObject p=profile("owner");JSONArray posts=p.getJSONArray("posts");for(int i=1;i<6;i++)posts.put(new JSONObject().put("text","Literal post "+i));String before=p.toString();call("renderProfilePage",new Class<?>[]{JSONObject.class,boolean.class},p,true);
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.msg(l10n_salon_social_scroll_posts,6).toString()));assertEquals(before,p.toString());}
        body.removeAllViews();call("renderProfilePage",new Class<?>[]{JSONObject.class,boolean.class},new JSONObject().put("handle","owner").put("name","Owner"),true);
        for(String code:CODES){Language.select(activity,code);layout();assertNotNull(find(body,Language.msg(l10n_salon_social_profile_empty).toString()));}
        int resource=com.eurobuddha.minimacore.i18n.R.plurals.l10n_salon_social_post_count;
        String[][] expected={{"post","posts","posts","posts"},{"публикация","публикации","публикаций","публикация"},{"допис","дописи","дописів","допис"},{"帖子","帖子","帖子","帖子"}};
        int[] counts={1,2,5,21};String[] languages={"en","ru","uk","zh-Hans"};
        for(int i=0;i<languages.length;i++){Language.select(activity,languages[i]);for(int j=0;j<counts.length;j++)assertEquals(expected[i][j],Language.count(resource,counts[j]).toString());}
    }
    @Test public void shareLinkAndVerificationLabelsKeepLiteralAddresses()throws Exception{
        String url="https://example.invalid/дані?q=中文";View row=(View)call("linkRow",new Class<?>[]{CharSequence.class,String.class},Language.msg(l10n_salon_social_profile_url),url);body.addView(row);
        TextView badge=(TextView)call("verifiedBadge",new Class<?>[]{String.class},"example.invalid");body.addView(badge);
        for(String code:CODES){Language.select(activity,code);assertNotNull(find(body,upper(l10n_salon_social_profile_url)));assertEquals(Language.msg(l10n_salon_social_verified_host,"example.invalid").toString(),badge.getText().toString());TextView link=find(body,url);assertNotNull(link);assertTrue(link.performLongClick());assertEquals(url,((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
            call("sharePage",new Class<?>[]{String.class},url);Intent chooser=Shadows.shadowOf(activity).getNextStartedActivity();assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());assertEquals(Language.msg(l10n_salon_social_share_chooser).toString(),chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());Intent send=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Language.msg(l10n_salon_social_share_text,"owner",url).toString(),send.getStringExtra(Intent.EXTRA_TEXT));}
        call("openUrl",new Class<?>[]{String.class},"file:///private/secret");assertEquals(Language.msg(l10n_salon_social_only_web).toString(),ShadowToast.getTextOfLatestToast());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }
}
