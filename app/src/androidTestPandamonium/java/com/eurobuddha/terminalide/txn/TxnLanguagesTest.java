package com.eurobuddha.terminalide.txn;

import android.content.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.terminalide.*;
import com.eurobuddha.terminalide.terminal.OutputFormatter;
import com.eurobuddha.terminalide.terminal.SessionExport;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Captures every transaction command. Never starts a node, signs, posts or moves real funds. */
@RunWith(AndroidJUnit4.class)
public class TxnLanguagesTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final String[] CODES={"zh-Hans","ru","uk","en"};
    static final String RAW="Literal 中文 Русский Українська 00012.34000 {value}";
    static final String ID="test_001";
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    static class FixtureNode extends NodeApi {
        final Map<String,Cb> callbacks=new LinkedHashMap<>();final List<String> commands=new ArrayList<>();
        FixtureNode(Context c){super(c,null);}
        @Override public void cmd(String command,Cb cb){commands.add(command);callbacks.put(command,cb);}
        void reply(String command,JSONObject result){Cb cb=callbacks.remove(command);assertNotNull(command,cb);cb.onResult(result);}
        void fail(String command,String error){Cb cb=callbacks.remove(command);assertNotNull(command,cb);cb.onError(error);}
    }
    static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    static TxnView txn(MainActivity a){return ((TerminalAdapter)field(a,"mAdapter")).getTxnView();}
    static FixtureNode freeze(MainActivity a){((NodeApi)field(a,"mNode")).onDestroy();FixtureNode n=new FixtureNode(a);txn(a).setNodeApi(n);txn(a).mTxnId.setText(ID);((androidx.viewpager.widget.ViewPager)a.findViewById(R.id.pm_terminalide_main_pager)).setCurrentItem(2);return n;}
    static void click(MainActivity a,int id){txn(a).getMainView().findViewById(id).performClick();}
    @SuppressWarnings("unchecked") static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)field(a,"languageDialogs");}
    static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    static List<TextView> texts(View v){List<TextView> out=new ArrayList<>();if(v instanceof TextView)out.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(texts(((ViewGroup)v).getChildAt(i)));return out;}
    static List<EditText> inputs(AlertDialog d){List<EditText> out=new ArrayList<>();for(TextView t:texts(d.getWindow().getDecorView()))if(t instanceof EditText)out.add((EditText)t);return out;}
    static void has(View root,Context c,int res,Object...args){String wanted=Language.text(c,res,args);for(TextView t:texts(root))if(t.getText().toString().equals(wanted))return;throw new AssertionError("Missing "+wanted);}
    void settle(){android.os.SystemClock.sleep(350);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    void screenshot(String name)throws Exception{settle();java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}}
    static JSONObject json(String s){try{return new JSONObject(s);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject data(String value){try{return new JSONObject().put("status",true).put("response",new JSONObject().put("data",value));}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject error(){try{return new JSONObject().put("status",false).put("error",RAW);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject balances(){return json("{\"status\":true,\"response\":{\"coins\":[{\"tokenid\":\"0x00\",\"input\":\"00012.34000\",\"output\":\"10.00000\",\"difference\":\"2.34000\"},{\"tokenid\":\"0xABCD\",\"input\":\"7.000\",\"output\":\"6.000\",\"difference\":\"1.000\"}],\"burn\":\"0.00010\",\"valid\":{\"basic\":true,\"signatures\":false,\"mmrproofs\":true,\"scripts\":false}}}");}
    @Test public void outputDialogPreservesDraftPrecisionAndCapturedId()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<MainActivity> original=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{original.set(a);node.set(freeze(a));txn(a).mTxnId.setSelection(3);click(a,R.id.pm_terminalide_txn_addoutput);retained.set(dialog(a));
                EditText amount=dialog(a).findViewById(R.id.pm_terminalide_out_amount);amount.setText("00012.34000");amount.setSelection(5);
                ((EditText)dialog(a).findViewById(R.id.pm_terminalide_out_address)).setText("0x001234");((EditText)dialog(a).findViewById(R.id.pm_terminalide_out_tokenid)).setText("0xABCD");CheckBox check=dialog(a).findViewById(R.id.pm_terminalide_out_storestate);assertFalse(check.isChecked());check.setChecked(true);
            });
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(original.get(),a);assertSame(retained.get(),dialog(a));assertEquals(ID,txn(a).mTxnId.getText().toString());assertEquals(3,txn(a).mTxnId.getSelectionStart());EditText amount=dialog(a).findViewById(R.id.pm_terminalide_out_amount);assertEquals("00012.34000",amount.getText().toString());assertEquals(5,amount.getSelectionStart());assertEquals(Language.text(a,l10n_terminal_txn_amount_hint),amount.getHint().toString());assertTrue(((CheckBox)dialog(a).findViewById(R.id.pm_terminalide_out_storestate)).isChecked());has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_txn_output_body);assertTrue(node.get().commands.isEmpty());});screenshot("txn-output-"+code);}
            s.onActivity(a->{txn(a).mTxnId.setText("next_draft");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
            String command="txnoutput id:"+ID+" amount:00012.34000 address:0x001234 tokenid:0xABCD storestate:true";
            s.onActivity(a->{assertEquals(Collections.singletonList(command),node.get().commands);node.get().reply(command,error());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{String status=txn(a).mStatus.getText().toString();assertTrue(status.contains(command));assertTrue(status.contains(RAW));assertTrue(status.contains(Language.text(a,l10n_terminal_txn_delete_tip)));assertEquals("next_draft",txn(a).mTxnId.getText().toString());});}
        }
    }
    @Test public void stateSignPostImportAndDeleteRemainExplicitAndLiteral()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->node.set(freeze(a)));
            int[] buttons={R.id.pm_terminalide_txn_setstate,R.id.pm_terminalide_txn_sign,R.id.pm_terminalide_txn_post,R.id.pm_terminalide_txn_import,R.id.pm_terminalide_txn_delete};
            int[] titles={l10n_terminal_txn_state_title,l10n_terminal_txn_sign_title,l10n_terminal_txn_post_title,l10n_terminal_txn_import_title,l10n_terminal_editor_delete_title};
            String[][] values={{"6","0xAA:BB"},{"0xAABB"},{"0.00010"},{"0x0000AAbb"},{}};
            String[] expected={"txnstate id:"+ID+" port:6 value:0xAA:BB","txnsign id:"+ID+" publickey:0xAABB","txnpost id:"+ID+" burn:0.00010","txnimport id:"+ID+" data:0x0000AAbb","txndelete id:"+ID};
            for(int i=0;i<buttons.length;i++){
                final int which=i;AtomicReference<AlertDialog> retained=new AtomicReference<>();
                s.onActivity(a->{click(a,buttons[which]);retained.set(dialog(a));List<EditText> fields=inputs(dialog(a));assertEquals(values[which].length,fields.size());for(int j=0;j<fields.size();j++){fields.get(j).setText(values[which][j]);fields.get(j).setSelection(1);}});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(retained.get(),dialog(a));has(dialog(a).getWindow().getDecorView(),a,titles[which],which==4?new Object[]{ID}:new Object[]{});List<EditText> fields=inputs(dialog(a));for(int j=0;j<fields.size();j++){assertEquals(values[which][j],fields.get(j).getText().toString());assertEquals(1,fields.get(j).getSelectionStart());}assertEquals(which,node.get().commands.size());});}
                s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();
                s.onActivity(a->{assertEquals(expected[which],node.get().commands.get(which));assertFalse(expected[which].contains("auto:true"));node.get().reply(expected[which],error());});
            }
        }
    }
    @Test public void changeFlowRetainsGateCancellationAmountsAndPartialFailure()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));click(a,R.id.pm_terminalide_txn_change);click(a,R.id.pm_terminalide_txn_change);assertEquals(1,node.get().commands.size());node.get().reply("txncheck id:"+ID,balances());retained.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(retained.get(),dialog(a));has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_txn_change_body,"2.34000 MINIMA\n1.000 0xABCD\n");assertEquals(true,field(txn(a),"mChangeInFlight"));assertEquals(1,node.get().commands.size());});}
            s.onActivity(a->dialog(a).cancel());settle();s.onActivity(a->{assertEquals(false,field(txn(a),"mChangeInFlight"));click(a,R.id.pm_terminalide_txn_change);assertEquals(2,node.get().commands.size());node.get().reply("txncheck id:"+ID,balances());dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
            s.onActivity(a->{assertEquals("getaddress",node.get().commands.get(2));node.get().reply("getaddress",json("{\"status\":true,\"response\":{\"miniaddress\":\"MxFixture001\"}}"));assertEquals("txnoutput id:"+ID+" amount:2.34000 address:MxFixture001 storestate:false",node.get().commands.get(3));node.get().reply(node.get().commands.get(3),json("{\"status\":true}"));assertEquals("txnoutput id:"+ID+" amount:1.000 address:MxFixture001 tokenid:0xABCD storestate:false",node.get().commands.get(4));node.get().reply(node.get().commands.get(4),error());assertEquals(false,field(txn(a),"mChangeInFlight"));});
            android.os.SystemClock.sleep(3500);
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(Language.text(a,l10n_terminal_txn_change_failed,2,2,RAW,"1.000 0xABCD\n"),txn(a).mStatus.getText().toString());assertEquals(OutputFormatter.COL_ERROR,txn(a).mStatus.getCurrentTextColor());assertEquals(5,node.get().commands.size());});screenshot("txn-change-failed-"+code);}
        }
    }
    @Test public void summaryKeepsPrecisionRejectsInvalidIdsAndIgnoresPreviousDraftReply()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<String> previous=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));for(String bad:new String[]{"a b","a:burn","a;txnpost","a\tb"}){txn(a).mTxnId.setText(bad);txn(a).refresh();}assertTrue(node.get().commands.isEmpty());txn(a).mTxnId.setText(ID);txn(a).refresh();Language.select(a,"uk");});settle();s.onActivity(a->node.get().reply("txncheck id:"+ID,balances()));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{String shown=txn(a).mSummary.getText().toString();assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_balance_row,"MINIMA","00012.34000","10.00000","2.34000")));assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_valid,true,false,true,false)));assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_burn_value,"0.00010")));assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_burn_warning)));assertEquals(OutputFormatter.COL_BOOL,txn(a).mSummary.getCurrentTextColor());});screenshot("txn-summary-"+code);}
            s.onActivity(a->{previous.set(txn(a).mSummary.getText().toString());txn(a).refresh();txn(a).mTxnId.setText("next_draft");node.get().reply("txncheck id:"+ID,json("{\"status\":false}"));assertEquals(previous.get(),txn(a).mSummary.getText().toString());txn(a).refresh();node.get().reply("txncheck id:next_draft",json("{\"status\":false}"));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_missing_txn,"next_draft"),txn(a).mSummary.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_list);node.get().reply("txnlist",json("{\"status\":true,\"response\":[{\"id\":\"raw{value}001\",\"transaction\":{\"inputs\":[{},{}],\"outputs\":[{}]}}]}"));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{String shown=txn(a).mStatus.getText().toString();assertTrue(shown.contains("raw{value}001"));assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_in_out,2,1)));assertTrue(shown.contains(Language.text(a,l10n_terminal_txn_inspect)));});}
        }
    }
    @Test public void exportCopiesLiteralDataAndRejectsOversizedPayloadWithoutReplacingClipboard()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));click(a,R.id.pm_terminalide_txn_export);node.get().reply("txnexport id:"+ID,data(RAW));ClipboardManager cb=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(RAW,cb.getPrimaryClip().getItemAt(0).getText().toString());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_exported,RAW.length()),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_export);node.get().reply("txnexport id:"+ID,data("中".repeat(SessionExport.MAX_INLINE_BYTES/3+1)));ClipboardManager cb=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(RAW,cb.getPrimaryClip().getItemAt(0).getText().toString());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_copy_failed),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_export);node.get().reply("txnexport id:"+ID,error());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_export_failed,RAW),txn(a).mStatus.getText().toString()));}
        }
    }
    @Test public void malformedRowsStayIncompleteAndCurrentNodeSignatureArraysUseAggregate()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));click(a,R.id.pm_terminalide_txn_addinput);node.get().reply("coins relevant:true sendable:true simplestate:true",json("{\"status\":true,\"response\":[null]}"));assertTrue(dialogs(a).isEmpty());assertEquals(Language.text(a,l10n_terminal_txn_incomplete),txn(a).mStatus.getText().toString());});
            for(String rows:new String[]{"[null]","[{\"tokenid\":\"0x00\",\"difference\":\"NaN\"}]","[{\"tokenid\":\"0x00\"}]"}){
                s.onActivity(a->{txn(a).refresh();node.get().reply("txncheck id:"+ID,json("{\"status\":true,\"response\":{\"coins\":"+rows+"}}"));click(a,R.id.pm_terminalide_txn_change);node.get().reply("txncheck id:"+ID,json("{\"status\":true,\"response\":{\"coins\":"+rows+"}}"));assertFalse((Boolean)field(txn(a),"mChangeInFlight"));assertTrue(node.get().callbacks.isEmpty());assertTrue(dialogs(a).isEmpty());});
                for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals(Language.text(a,l10n_terminal_txn_incomplete),txn(a).mSummary.getText().toString());assertEquals(Language.text(a,l10n_terminal_txn_incomplete),txn(a).mStatus.getText().toString());assertEquals(OutputFormatter.COL_ERROR,txn(a).mSummary.getCurrentTextColor());});}
            }
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_change);node.get().reply("txncheck id:"+ID,json("{\"status\":true,\"response\":{\"coins\":[{\"tokenid\":\"0x00\",\"difference\":\"-0.00001\"}]}}"));assertFalse((Boolean)field(txn(a),"mChangeInFlight"));assertTrue(node.get().callbacks.isEmpty());assertTrue(dialogs(a).isEmpty());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_negative_change),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{txn(a).refresh();JSONObject response=balances();try{response.getJSONObject("response").put("allsignaturesvalid",true).getJSONObject("valid").put("signatures",new JSONArray().put(new JSONObject().put("valid",true)));}catch(Exception e){throw new AssertionError(e);}node.get().reply("txncheck id:"+ID,response);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertTrue(txn(a).mSummary.getText().toString().contains(Language.text(a,l10n_terminal_txn_valid,true,true,true,false))));}
            s.onActivity(a->{txn(a).refresh();JSONObject response=balances();try{response.getJSONObject("response").getJSONObject("valid").put("signatures",new JSONArray());}catch(Exception e){throw new AssertionError(e);}node.get().reply("txncheck id:"+ID,response);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertTrue(txn(a).mSummary.getText().toString().contains(Language.text(a,l10n_terminal_txn_valid,true,"?",true,false))));}
        }
    }
    @Test public void completedStatusesAndOfflineErrorsDoNotResurrectEarlierMessages()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));click(a,R.id.pm_terminalide_txn_create);node.get().reply("txncreate id:"+ID,json("{\"status\":true}"));node.get().reply("txncheck id:"+ID,json("{\"status\":true,\"response\":{}}"));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertEquals("✓ "+Language.text(a,l10n_terminal_txn_created,ID),txn(a).mStatus.getText().toString());assertEquals(Language.text(a,l10n_terminal_txn_exists),txn(a).mSummary.getText().toString());});}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_basics);node.get().reply("txnbasics id:"+ID,json("{\"status\":true}"));node.get().reply("txncheck id:"+ID,json("{\"status\":false}"));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals("✓ "+Language.text(a,l10n_terminal_txn_proofs_added),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_change);node.get().reply("txncheck id:"+ID,json("{\"status\":true,\"response\":{\"coins\":[{\"tokenid\":\"0x00\",\"difference\":\"0.00000\"}]}}"));assertEquals(false,field(txn(a),"mChangeInFlight"));assertTrue(dialogs(a).isEmpty());assertTrue(node.get().callbacks.isEmpty());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_txn_balanced),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_list);node.get().fail("txnlist",NodeApi.ERR_NOT_ENABLED);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_explorer_node_retry),txn(a).mStatus.getText().toString()));}
            s.onActivity(a->{click(a,R.id.pm_terminalide_txn_list);node.get().fail("txnlist",RAW);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals("✖ "+RAW,txn(a).mStatus.getText().toString()));}
        }
    }
    @Test public void coinPickerCapAndInvalidOutputCannotAlterCommandValues()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            s.onActivity(a->{node.set(freeze(a));click(a,R.id.pm_terminalide_txn_addinput);JSONArray coins=new JSONArray();try{for(int i=0;i<101;i++)coins.put(new JSONObject().put("coinid","0xCoin"+i).put("tokenid","0x00").put("amount","0012.34000"));node.get().reply("coins relevant:true sendable:true simplestate:true",new JSONObject().put("status",true).put("response",coins));}catch(Exception e){throw new AssertionError(e);}retained.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(retained.get(),dialog(a));has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_txn_coin_title_capped,100,101);ListAdapter items=dialog(a).getListView().getAdapter();assertEquals(100,items.getCount());assertEquals("0012.34000 MINIMA\n0xCoin0",items.getItem(0));assertEquals(1,node.get().commands.size());});}
            s.onActivity(a->{AlertDialog d=dialog(a);d.getListView().performItemClick(d.getListView().getChildAt(0),0,0);});settle();
            s.onActivity(a->{assertEquals("txninput id:"+ID+" coinid:0xCoin0",node.get().commands.get(1));node.get().reply(node.get().commands.get(1),error());click(a,R.id.pm_terminalide_txn_addoutput);((EditText)dialog(a).findViewById(R.id.pm_terminalide_out_amount)).setText("1");((EditText)dialog(a).findViewById(R.id.pm_terminalide_out_address)).setText("0xFF;txnpost");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();s.onActivity(a->assertEquals(2,node.get().commands.size()));
        }
    }
}
