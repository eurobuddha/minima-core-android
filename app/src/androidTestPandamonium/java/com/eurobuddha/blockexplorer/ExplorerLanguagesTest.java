package com.eurobuddha.blockexplorer;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Display-only public fixtures. Never starts a node or sends a command to one. */
@RunWith(AndroidJUnit4.class)
public class ExplorerLanguagesTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES = {"zh-Hans", "ru", "uk", "en"};
    private static final String ID = "0x0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ADDRESS = "MxDISPOSABLEPUBLICADDRESS0123456789";
    private static final String TOKEN = "Literal 中文 Русский Українська";
    private static final long TIME = 1577880123000L;
    @Before public void prepare() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu", Build.HARDWARE);
        assertNull(MinimaService.minima);
        assertNull(org.minima.system.Main.getInstance());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppLock.disable(context); Language.select(context, "en");
        });
    }
    @After public void reset() {
        if (!"ranchu".equals(Build.HARDWARE) || !"true".equals(
                InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"))) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> Language.select(context, "en"));
        assertNull(MinimaService.minima);
        assertNull(org.minima.system.Main.getInstance());
    }
    private static Object field(Object target, String name) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void field(Object target, String name, Object value) {
        try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static Object invoke(MainActivity a, String name, Class<?>[] types, Object... values) {
        try { Method m = MainActivity.class.getDeclaredMethod(name,types); m.setAccessible(true); return m.invoke(a,values); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static JSONObject json(String value) {
        try { return new JSONObject(value); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static JSONObject fixture(boolean transaction) {
        try {
            JSONObject coin = new JSONObject().put("amount","999").put("tokenamount","12.340000")
                    .put("tokenid","0x1234").put("miniaddress",ADDRESS)
                    .put("token",new JSONObject().put("name",new JSONObject().put("name",TOKEN)));
            JSONObject txn = new JSONObject().put("inputs",new JSONArray())
                    .put("outputs",new JSONArray().put(coin))
                    .put("state",new JSONArray().put(new JSONObject().put("port",7).put("data",TOKEN)));
            return new JSONObject().put("txpowid",ID).put("isblock",!transaction).put("istransaction",transaction)
                    .put("size","1024").put("burn","0.010000").put("superblock",3)
                    .put("header",new JSONObject().put("block","1234567").put("timemilli",String.valueOf(TIME))
                            .put("blkdiff","0x1234").put("cascadelevels",4)
                            .put("superparents",new JSONArray().put(new JSONObject().put("parent",ID))))
                    .put("body",new JSONObject().put("txnlist",new JSONArray()).put("txn",txn));
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private static final class FixtureNode extends NodeApi {
        final Map<String,Cb> pending = new HashMap<>();
        FixtureNode(Context context) { super(context,null); }
        @Override public void cmd(String command,Cb cb) { pending.put(command,cb); }
        void answer(String command,JSONObject result) {
            Cb cb = pending.remove(command); assertNotNull(command,cb); cb.onResult(result);
        }
    }
    private static FixtureNode freeze(MainActivity a) {
        ((NodeApi)field(a,"node")).onDestroy();
        ((Handler)field(a,"ui")).removeCallbacksAndMessages(null);
        FixtureNode fake = new FixtureNode(a); field(a,"node",fake); return fake;
    }
    @SuppressWarnings("unchecked") private static List<View> pages(MainActivity a) { return (List<View>)field(a,"pageStack"); }
    private static View top(MainActivity a) { List<View> pages = pages(a); return pages.get(pages.size()-1); }
    private static void collect(View view,List<TextView> out) {
        if (view instanceof TextView) out.add((TextView)view);
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) collect(((ViewGroup)view).getChildAt(i),out);
    }
    private static TextView text(View root,String wanted) {
        List<TextView> texts = new ArrayList<>(); collect(root,texts);
        for (TextView v:texts) if (wanted.equals(v.getText().toString())) return v;
        throw new AssertionError("Missing text: "+wanted+"; available: "+texts.stream().map(v->v.getText().toString()).collect(java.util.stream.Collectors.toList()));
    }
    private static TextView message(View root,Context c,int resource,Object... args) { return text(root,Language.text(c,resource,args)); }
    private void clipboard(String expected) {
        ClipboardManager cb=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cb.getPrimaryClip()); assertEquals(expected,cb.getPrimaryClip().getItemAt(0).getText().toString());
    }
    private void settle() { android.os.SystemClock.sleep(350); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
    private void screenshot(String name) throws Exception {
        settle();
        java.io.File directory=new java.io.File(context.getExternalFilesDir(null),"language-tests");
        assertTrue(directory.isDirectory()||directory.mkdirs());
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        } finally {bitmap.recycle();}
    }
    @Test public void homeAndExpandedBlockKeepDraftDataAndViewIdentity() throws Exception {
        JSONObject block=fixture(false); String pretty=block.toString(2);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<MainActivity> original=new AtomicReference<>();
            AtomicReference<View> page=new AtomicReference<>(); AtomicReference<TextView> raw=new AtomicReference<>();
            scenario.onActivity(a->{
                original.set(a); freeze(a);
                EditText search=(EditText)field(a,"search"); search.setText(ADDRESS);search.setSelection(5);
                invoke(a,"showBlockDetail",new Class<?>[]{JSONObject.class},block);page.set(top(a));
                message(top(a),a,l10n_explorer_raw_show).performClick();raw.set(text(top(a),pretty));
            });
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertSame(original.get(),a);assertSame(page.get(),top(a));assertEquals(1,pages(a).size());
                    EditText search=(EditText)field(a,"search");assertEquals(ADDRESS,search.getText().toString());assertEquals(5,search.getSelectionStart());
                    assertEquals(Language.text(a,l10n_explorer_search_hint),search.getHint().toString());
                    message((View)field(a,"homeView"),a,l10n_explorer_latest);
                    message(top(a),a,l10n_explorer_block_detail);message(top(a),a,l10n_explorer_no_transactions);
                    message(top(a),a,l10n_explorer_bytes,"1024");message(top(a),a,l10n_explorer_raw_hide);
                    text(top(a),Language.join(Util.dateTime(TIME),"   ·   ",Util.relative(TIME)).toString());
                    assertSame(raw.get(),text(top(a),pretty));assertTrue(raw.get().performLongClick());clipboard(pretty);
                    ((View)text(top(a),ID).getParent()).performClick();clipboard(ID);
                });
                screenshot("explorer-block-"+code);
            }
            scenario.onActivity(a->{
                message(top(a),a,l10n_explorer_raw_hide).performClick();message(top(a),a,l10n_explorer_raw_show);
                invoke(a,"popPage",new Class<?>[]{});assertTrue(pages(a).isEmpty());assertEquals(View.VISIBLE,((View)field(a,"homeView")).getVisibility());
            });
        }
    }
    @Test public void transactionLateConfirmationsCoinsAndStateSwitchTogether() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> page=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));invoke(a,"showTxnDetail",new Class<?>[]{JSONObject.class},fixture(true));page.set(top(a));});
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertSame(page.get(),top(a));
                    if(code.equals(CODES[0])) node.get().answer("txpow onchain:"+ID,json("{\"response\":{\"found\":true,\"block\":\"1234567\",\"confirmations\":\"42\"}}"));
                    message(top(a),a,l10n_explorer_on_chain,"1234567","42");
                    message(top(a),a,l10n_explorer_state);message(top(a),a,l10n_explorer_none);message(top(a),a,l10n_explorer_no);
                    text(top(a),"+12.34  "+TOKEN);
                    message(top(a),a,l10n_explorer_tap_copy,Util.shorten(ADDRESS)).performClick();clipboard(ADDRESS);
                    ((View)text(top(a),TOKEN).getParent()).performClick();clipboard(TOKEN);
                    message(top(a),a,l10n_explorer_tap_copy,Language.msg(l10n_explorer_port,"7"));
                });
                screenshot("explorer-transaction-"+code);
            }
            scenario.onActivity(a->{
                invoke(a,"showTxnDetail",new Class<?>[]{JSONObject.class},fixture(true));
                node.get().answer("txpow onchain:"+ID,json("{\"response\":{\"found\":false}}"));
            });
            for(String code:CODES) {scenario.onActivity(a->{Language.select(a,code);message(top(a),a,l10n_explorer_off_chain);});settle();}
        }
    }
    @Test public void addressResultsKeepBothKindsAndBackStack() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<View> page=new AtomicReference<>();
            scenario.onActivity(a->{freeze(a);invoke(a,"showAddressResults",new Class<?>[]{String.class,JSONArray.class},ADDRESS,new JSONArray().put(fixture(false)).put(fixture(true)));page.set(top(a));});
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertSame(page.get(),top(a));message(top(a),a,l10n_explorer_address_count,2);
                    message(top(a),a,l10n_explorer_block_badge);message(top(a),a,l10n_explorer_txn_badge);
                    text(top(a),Util.shorten(ADDRESS)).performClick();clipboard(ADDRESS);
                    ((View)message(top(a),a,l10n_explorer_txn_badge).getParent()).performClick();
                    assertEquals(2,pages(a).size());message(top(a),a,l10n_history_transaction);
                    invoke(a,"popPage",new Class<?>[]{});assertSame(page.get(),top(a));
                });
            }
            screenshot("explorer-address-en");
        }
    }
    @SuppressWarnings("unchecked")
    @Test public void homeStatisticsAndVisibleRowsUpdateWithoutReloadingChain() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<FixtureNode> fake=new AtomicReference<>();
            scenario.onActivity(a->{
                fake.set(freeze(a));
                ((View)field(a,"pairingBanner")).setVisibility(View.GONE);
                List<JSONObject> blocks=(List<JSONObject>)field(a,"blocks");
                blocks.add(fixture(false));blocks.add(fixture(true));
                ((RecyclerView.Adapter<?>)field(a,"adapter")).notifyDataSetChanged();
                invoke(a,"fetchStats",new Class<?>[]{});
            });
            // Earlier clipboard tests may have queued Android toasts; let those disappear.
            android.os.SystemClock.sleep(6000);
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    if(code.equals(CODES[0])) {
                        fake.get().answer("status",json("{\"response\":{\"chain\":{\"speed\":\"0.02\"}}}"));
                        fake.get().answer("txpow action:info",json("{\"response\":{\"txpowdb\":{\"size\":1234}}}"));
                    }
                    View home=(View)field(a,"homeView");
                    message(home,a,l10n_explorer_tip_height);message(home,a,l10n_explorer_block_time);
                    message(home,a,l10n_explorer_seconds,"50.0");message(home,a,l10n_explorer_txn_total,"1,234");
                    message(home,a,l10n_explorer_transactions_badge,1);message(home,a,l10n_explorer_empty);
                    assertEquals(2,((List<?>)field(a,"blocks")).size());
                });
                screenshot("explorer-home-"+code);
            }
            scenario.onActivity(a->{
                Language.select(a,"uk");invoke(a,"showBlockDetail",new Class<?>[]{JSONObject.class},fixture(false));
                message(top(a),a,l10n_explorer_raw_show).performClick();
                ((android.widget.ScrollView)((ViewGroup)top(a)).getChildAt(1)).post(()->
                    ((android.widget.ScrollView)((ViewGroup)top(a)).getChildAt(1)).fullScroll(View.FOCUS_DOWN));
            });
            screenshot("explorer-raw-uk");
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    @Test public void recycledBlockRowsAndFooterDoNotKeepOldLanguageOrState() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                freeze(a);
                List<JSONObject> blocks=(List<JSONObject>)field(a,"blocks"); blocks.add(fixture(false));blocks.add(fixture(true));
                RecyclerView.Adapter adapter=(RecyclerView.Adapter)field(a,"adapter");RecyclerView list=(RecyclerView)field(a,"recycler");
                RecyclerView.ViewHolder row=adapter.createViewHolder(list,0),footer=adapter.createViewHolder(list,1);
                for(String code:CODES) {
                    Language.select(a,code);
                    adapter.onBindViewHolder(row,0);message(row.itemView,a,l10n_explorer_empty);
                    adapter.onBindViewHolder(row,1);message(row.itemView,a,l10n_explorer_transactions_badge,1);
                    text(row.itemView,"#1,234,567");
                    for(int state=0;state<3;state++) {
                        field(a,"loading",state==1);field(a,"endOfChain",state==2);adapter.onBindViewHolder(footer,2);
                        if(state==0) message(footer.itemView,a,l10n_explorer_older);
                        else if(state==1) message(footer.itemView,a,l10n_explorer_loading);
                        else text(footer.itemView,Language.join("· ",Language.msg(l10n_explorer_chain_end)," ·").toString());
                    }
                }
            });
        }
    }
}
