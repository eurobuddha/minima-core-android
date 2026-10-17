package com.eurobuddha.terminalide.terminal;

import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.terminalide.MainActivity;
import com.eurobuddha.terminalide.NodeApi;
import com.eurobuddha.terminalide.R;
import com.eurobuddha.terminalide.TerminalAdapter;
import com.google.android.material.tabs.TabLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public, disposable fixtures. Every command callback is captured; no node is started. */
@RunWith(AndroidJUnit4.class)
public class TerminalLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"zh-Hans","ru","uk","en"};
    private static final String RAW="Literal 中文 Русский Українська 00012.34000 {value}";
    @Before public void prepare() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        context.deleteDatabase(HistoryDB.DATABASE_NAME);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset() {
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    private static Object field(Object target,String name) {
        try {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void invoke(Object target,String name,Class<?>[] types,Object...args) {
        try {Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static TerminalView terminal(MainActivity a) {return ((TerminalAdapter)field(a,"mAdapter")).getTerminalView();}
    private static class FixtureNode extends NodeApi {
        final Map<String,Cb> callbacks=new LinkedHashMap<>();
        FixtureNode(Context context){super(context,null);}
        @Override public void cmd(String command,Cb cb){callbacks.put(command,cb);}
        void reply(String command,JSONObject json){Cb cb=callbacks.remove(command);assertNotNull(command,cb);cb.onResult(json);}
        void fail(String command,String message){Cb cb=callbacks.remove(command);assertNotNull(command,cb);cb.onError(message);}
    }
    private static FixtureNode freeze(MainActivity a) {
        ((NodeApi)field(a,"mNode")).onDestroy();FixtureNode fake=new FixtureNode(a);terminal(a).setNodeApi(fake);return fake;
    }
    @SuppressWarnings("unchecked") private static Set<AlertDialog> dialogs(MainActivity a){return (Set<AlertDialog>)field(a,"languageDialogs");}
    private static AlertDialog dialog(MainActivity a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    private static List<TextView> texts(View root) {
        List<TextView> result=new ArrayList<>();if(root instanceof TextView)result.add((TextView)root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(texts(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    private static TextView text(View root,String wanted) {
        for(TextView view:texts(root))if(wanted.equals(view.getText().toString()))return view;
        throw new AssertionError("Missing "+wanted);
    }
    private static void message(View root,Context c,int resource,Object...args){text(root,Language.text(c,resource,args));}
    private static JSONObject response() {
        try{return new JSONObject().put("status",false).put("error",RAW).put("response",new JSONObject().put("amount","00012.34000"));}
        catch(Exception e){throw new AssertionError(e);}
    }
    private void settle(){android.os.SystemClock.sleep(400);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private void screenshot(String name)throws Exception{
        settle();java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());
        android.graphics.Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    @Test public void shellAndFavoriteDialogKeepDraftCursorAndDatabaseValues()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<MainActivity> original=new AtomicReference<>();AtomicReference<AlertDialog> open=new AtomicReference<>();AtomicReference<EditText> name=new AtomicReference<>();
            scenario.onActivity(a->{
                original.set(a);freeze(a);TerminalView terminal=terminal(a);
                terminal.mInput.setText("status clean:true");terminal.mInput.setSelection(8);
                invoke(a,"setBlock",new Class<?>[]{String.class},"0001234");
                invoke(terminal,"saveFavorite",new Class<?>[]{});open.set(dialog(a));
                for(TextView view:texts(open.get().getWindow().getDecorView()))if(view instanceof EditText){name.set((EditText)view);break;}
                assertNotNull(name.get());name.get().setText(RAW);name.get().setSelection(5);
            });
            for(String code:CODES){
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertSame(original.get(),a);assertSame(open.get(),dialog(a));TerminalView terminal=terminal(a);
                    assertEquals("status clean:true",terminal.mInput.getText().toString());assertEquals(8,terminal.mInput.getSelectionStart());
                    assertEquals(RAW,name.get().getText().toString());assertEquals(5,name.get().getSelectionStart());
                    assertEquals(Language.text(a,l10n_terminal_ide_favorite_name),name.get().getHint().toString());
                    message(dialog(a).getWindow().getDecorView(),a,l10n_terminal_ide_save_favorite);
                    assertEquals(Language.text(a,l10n_common_save),dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                    Toolbar toolbar=a.findViewById(R.id.pm_terminalide_toolbar);
                    assertEquals(Language.text(a,l10n_terminal_ide_block,"0001234"),toolbar.getSubtitle().toString());
                    TabLayout tabs=a.findViewById(R.id.pm_terminalide_tabs);assertEquals(Language.text(a,l10n_menu_logs),tabs.getTabAt(3).getText().toString());
                    assertTrue(terminal.mOutput.getChildCount()>0);
                    assertTrue(((TextView)terminal.mOutput.getChildAt(0)).getText().toString().contains(Language.text(a,l10n_terminal_ide_banner)));
                });
                screenshot("terminal-favorite-"+code);
            }
            scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());settle();
            scenario.onActivity(a->{List<String[]> values=terminal(a).mHistory.getFavorites();assertEquals(1,values.size());assertEquals(RAW,values.get(0)[1]);assertEquals("status clean:true",values.get(0)[2]);});
        }
    }
    @Test public void lateReplyKeepsRawSpansSelectionAndCompletedStatusAcrossLanguages()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<TerminalLabel> output=new AtomicReference<>();AtomicReference<String> raw=new AtomicReference<>();
            raw.set(response().toString(2));
            scenario.onActivity(a->{node.set(freeze(a));terminal(a).runCMD("status");output.set((TerminalLabel)terminal(a).mOutput.getChildAt(2));terminal(a).setSelectionMode(true);});
            scenario.onActivity(a->Language.select(a,"uk"));settle();
            scenario.onActivity(a->{assertEquals(Language.text(a,l10n_terminal_ide_running),output.get().getText().toString());node.get().reply("status",response());Selection.setSelection((Spannable)output.get().getText(),2,9);});
            for(String code:CODES){
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertSame(output.get(),terminal(a).mOutput.getChildAt(2));assertTrue(terminal(a).isSelectionMode());
                    String shown=output.get().getText().toString();assertTrue(shown,shown.startsWith(raw.get()+"\n"));assertTrue(shown,shown.contains(Language.text(a,l10n_terminal_ide_command_failed)));
                    assertEquals(2,output.get().getSelectionStart());assertEquals(9,output.get().getSelectionEnd());
                    Spanned styled=(Spanned)output.get().getText();assertTrue(styled.getSpans(0,raw.get().length(),ForegroundColorSpan.class).length>4);
                    assertTrue(terminal(a).exportText().contains(RAW));
                    Toolbar toolbar=a.findViewById(R.id.pm_terminalide_toolbar);assertEquals(Language.text(a,l10n_terminal_ide_exit_select),toolbar.getMenu().findItem(5).getTitle().toString());
                });
            }
            scenario.onActivity(a->{terminal(a).setSelectionMode(false);terminal(a).runCMD("status clean:true");node.get().fail("status clean:true",RAW);output.set((TerminalLabel)terminal(a).mOutput.getChildAt(4));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->assertEquals("✖ "+RAW,output.get().getText().toString()));}
            screenshot("terminal-output-en");
        }
    }
    @Test public void riskWarningsRemainGuardedAndDialogsCloseWithActivity()throws Exception{
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<FixtureNode> node=new AtomicReference<>();scenario.onActivity(a->node.set(freeze(a)));
            assertNull(CommandRegistry.dangerWarning("coins depth:4"));assertNull(CommandRegistry.dangerWarning("history max:8 offset:0"));assertNull(CommandRegistry.dangerWarning("printtree depth:10"));
            String[] commands={"coins","status; history max:999999999999999999999999999","printtree"};
            for(String command:commands){
                scenario.onActivity(a->{terminal(a).runCMD(command);retained.set(dialog(a));assertTrue(node.get().callbacks.isEmpty());});
                for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                    assertSame(retained.get(),dialog(a));message(dialog(a).getWindow().getDecorView(),a,l10n_terminal_ide_risky);
                    text(dialog(a).getWindow().getDecorView(),TerminalMessages.message(CommandRegistry.dangerWarning(command)).toString());
                    assertEquals(Language.text(a,l10n_terminal_ide_run_anyway),dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                    assertTrue(node.get().callbacks.isEmpty());
                });}
                scenario.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();
            }
            scenario.onActivity(a->{terminal(a).runCMD("coins");retained.set(dialog(a));});
        }
        assertFalse(retained.get().isShowing());
    }
    @Test public void bannerAndCompletionRetainHighlightAndKnownErrorsRefresh()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> row=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));terminal(a).mInput.setText("txninput ");terminal(a).mInput.setSelection(9);invoke(terminal(a),"moveSelection",new Class<?>[]{int.class},1);assertEquals(1,field(terminal(a),"mSel"));row.set(terminal(a).mDropdown.getChildAt(0));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                TerminalView terminal=terminal(a);assertEquals("txninput ",terminal.mInput.getText().toString());
                assertEquals(1,field(terminal,"mSel"));assertSame(row.get(),terminal.mDropdown.getChildAt(0));
                boolean found=false;
                for(TextView text:texts(terminal.mDropdown))if(text.getText().toString().contains(Language.text(a,l10n_terminal_ide_required)))found=true;
                assertTrue("Required marker must translate",found);
                TextView banner=(TextView)terminal.mOutput.getChildAt(0);assertNotNull(banner.getLayout());
                assertEquals("ASCII header must fit on one line",banner.getText().toString().indexOf('\n')+1,banner.getLayout().getLineEnd(0));
            });}
            scenario.onActivity(a->{terminal(a).mInput.setText("");terminal(a).runCMD("status");node.get().fail("status",NodeApi.ERR_NOT_ENABLED);terminal(a).setSelectionMode(true);});
            android.os.SystemClock.sleep(4500); // let the selection instruction toast clear before visual capture
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                TerminalView terminal=terminal(a);assertEquals(Language.text(a,l10n_explorer_node_retry),((TextView)terminal.mOutput.getChildAt(2)).getText().toString());
                message(terminal.getMainView(),a,l10n_terminal_ide_copy_all);message(terminal.getMainView(),a,l10n_terminal_ide_done);
            });screenshot("terminal-console-"+code);}
            scenario.onActivity(a->{terminal(a).setSelectionMode(false);terminal(a).runCMD("status clean:true");node.get().fail("status clean:true","The embedded node did not respond. Check its status in Minima Core.");});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->assertEquals("✖ "+Language.text(a,l10n_terminal_ide_timeout),((TextView)terminal(a).mOutput.getChildAt(4)).getText().toString()));}
        }
    }
    @Test public void exportDialogKeepsSnapshotAndClipboardSizeGuard()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<String> snapshot=new AtomicReference<>();AtomicReference<AlertDialog> open=new AtomicReference<>();
            scenario.onActivity(a->{freeze(a);invoke(terminal(a),"appendEntry",new Class<?>[]{CharSequence.class},RAW);snapshot.set(terminal(a).exportText());invoke(a,"showExportDialog",new Class<?>[]{});open.set(dialog(a));});
            for(String code:CODES){scenario.onActivity(a->Language.select(a,code));settle();scenario.onActivity(a->{
                assertSame(open.get(),dialog(a));message(dialog(a).getWindow().getDecorView(),a,l10n_terminal_ide_export_title,SessionExport.utf8Len(snapshot.get())/1024);
                message(dialog(a).getWindow().getDecorView(),a,l10n_terminal_ide_save_file);
                message(dialog(a).getWindow().getDecorView(),a,l10n_terminal_ide_copy_clipboard);
            });}
            scenario.onActivity(a->dialog(a).getListView().performItemClick(dialog(a).getListView().getChildAt(2),2,2));settle();
            scenario.onActivity(a->{
                ClipboardManager cb=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                assertNotNull(cb.getPrimaryClip());assertEquals(snapshot.get(),cb.getPrimaryClip().getItemAt(0).getText().toString());
                assertFalse(SessionExport.copy(a,"中".repeat(SessionExport.MAX_INLINE_BYTES/3+1),"fixture"));
                assertEquals(snapshot.get(),cb.getPrimaryClip().getItemAt(0).getText().toString());
            });
            java.io.File file=new java.io.File(context.getCacheDir(),"terminal-language-public-fixture.txt");
            SessionExport.writeTo(context,Uri.fromFile(file),snapshot.get());assertEquals(snapshot.get(),new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));assertTrue(file.delete());
        }
    }
    private JSONObject bundledHelp() throws Exception {
        try(java.io.InputStream input=context.getAssets().open("help.json")) {
            return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static String suggestionFingerprint(Suggest.Result result) {
        StringBuilder out=new StringBuilder(String.valueOf(result.paramHint));
        for(Suggest.Item item:result.items)out.append('\n').append(item.label).append('|').append(item.insert)
            .append('|').append(item.tokenStart).append('|').append(item.required).append('|').append(item.kind).append('|').append(item.desc);
        return out.toString();
    }
    @Test public void bundledHelpTranslatesWithoutChangingParserOrCompletionData() throws Exception {
        JSONObject bundled=bundledHelp();assertEquals(115,bundled.length());
        Map<String,Map<String,ParamDocs.Doc>> docs=new LinkedHashMap<>();
        Map<String,String> suggestions=new LinkedHashMap<>();
        for(java.util.Iterator<String> it=bundled.keys();it.hasNext();) {
            String name=it.next();docs.put(name,ParamDocs.forCommand(context,name));
            suggestions.put(name,suggestionFingerprint(Suggest.suggest(context,name+" ",name.length()+1)));
        }
        String[] pages={"block","burn","getaddress","newaddress","checkaddress","mempool","healthcheck","status","random","coinexport","coinimport","help","keys","txncreate","txninput","txnoutput","txnstate","txnsign","txnbasics","txncheck","txnpost","txnexport","txnimport","txnlist","txndelete","backup","balance","coincheck","coinnotify","coins","cointrack","decryptbackup","history","reset","restore","restoresync","vault","sign","verify","seedrandom","hash","convert","txnaddamount","txnauto","txnclear","txncoinlock","txnlock","txnmine","txnminepost","txnmmr","txnscript","txnview","automine","connect","debugflag","disconnect","hashtest","incentivecash","logs","maths","message","network","p2pstate","peers","ping","printmmr","printtree","quit","rpc","scanchain","slavenode","systemcheck","test","timemilli","trace","tutorial","webhooks","whitepaper","benchmark","jniminingtest","jnitest","send","sendnosign","sendpoll","sendpost","sendsign","sendview","consolidate","tokencreate","tokens","tokenvalidate","txpow","consolidatefrom","constructfrom","createfrom","createtokenfrom","postfrom","rawfrom","rawtxnfrom","sendfrom","signfrom","mmrcreate","mmrproof","newscript","removescript","runscript","scripts","sphincs","archive","megammr","megammrsync","multisig","multisigread","mysql","mysqlcoins"};
        for(String code:CODES) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,code));
            for(String name:docs.keySet()) {
                String raw=bundled.getJSONObject(name).getString("help");String description=ParamDocs.stripParams(raw);
                assertTrue(name,HelpText.message(description) instanceof Language.Message);
                String prefix=raw.substring(0,raw.lastIndexOf(description));
                String translated=HelpText.brief(context,name).toString();assertTrue(name,translated.startsWith(prefix));
                if(code.equals("en"))assertEquals(raw,translated);else assertNotEquals(name,raw,translated);
                assertSame(docs.get(name),ParamDocs.forCommand(context,name));
                assertEquals(name,suggestions.get(name),suggestionFingerprint(Suggest.suggest(context,name+" ",name.length()+1)));
                assertEquals(raw,HelpStore.brief(context,name));
                assertEquals(bundled.getJSONObject(name).getString("fullhelp").trim(),HelpStore.full(context,name));
            }
            for(String name:pages) {
                String original=HelpStore.full(context,name),translated=HelpText.full(context,name).toString();
                if(code.equals("en") && !Arrays.asList("getaddress","keys","txninput","txnsign","decryptbackup","reset","vault","sign","txnview","restoresync","benchmark","jniminingtest","jnitest","printmmr","tokens","consolidatefrom","createtokenfrom","sendfrom","signfrom","rawtxnfrom","removescript","sphincs","archive","megammrsync","multisigread","mysql","mysqlcoins").contains(name))assertEquals(original,translated);else assertNotEquals(name,original,translated);
                if(name.equals("getaddress") || name.equals("keys")){assertTrue(translated.contains("keys action:list"));assertTrue(translated.contains("maxuses"));assertFalse(translated.contains("262144"));}
                assertTrue(name,translated.startsWith(name+"\n\n"));
                String examples=original.substring(original.indexOf("Examples:\n\n")+11);
                if(name.equals("sign"))examples=examples.replace("sign data:0xCD34..","sign data:0xCD34.. publickey:0xFED5..");
                if(Arrays.asList("benchmark","jniminingtest","jnitest").contains(name)) {
                    examples=name+"\n\n"+name+(name.equals("benchmark")?" hashes:2000000":" maxattempts:2000000");
                    assertFalse(translated.contains("noncetest"));assertFalse(translated.contains("amount: ("));
                }
                if(name.equals("printmmr") && code.equals("en"))assertTrue(translated.contains("Row 0 represents the leaf nodes"));
                if(name.equals("rawtxnfrom"))examples="rawtxnfrom inputs:[\"0x859EC55D1DC0DD66E72C1D8B6CDBD5E12D0EC5CB56255F629BAEC590047C22C4\"] scripts:[\"RETURN SIGNEDBY(0x677228489AD4C14AC0D04F9BA054974A2B1D60C5A8E8D710CF9F2BF64D1EE81E)\"] outputs:[{\"address\":\"0x6A3A060CE9D8E876E9B12DBE5D8F6A6864183BA1A63EA8CBCC14D668B5BECACF\",\"amount\":\"9.9996\",\"storestate\":false,\"tokenid\":\"0x00\"}] state:{\"0\":\"98\",\"1\":\"[MESSAGE]\"}";
                if(name.equals("sphincs")) {
                    examples=examples.replace("signature:0x..|file:thefile","signature:0x..\n\nsphincs action:verify data:0x001122 publickey:0x.. file:thefile");
                    assertFalse(translated.contains("verify - sign a piece of data"));
                    assertTrue(translated.contains("signature:0x.."));assertTrue(translated.contains("file:thefile"));
                }
                if(Arrays.asList("consolidatefrom","createtokenfrom","sendfrom","signfrom").contains(name)) {
                    assertTrue(translated.contains("blockaskeyuses"));assertFalse(translated.contains("increase this by one"));
                    if(code.equals("en")){assertTrue(translated.contains("Required key-use position"));assertTrue(translated.contains("next unused leaf index"));assertTrue(translated.contains("lower bound"));}
                }
                if(name.equals("runscript")){assertTrue(translated.contains("{@GLOBAL:value,..}"));assertTrue(translated.contains("{script:proof,..}"));}
                if(name.equals("multisigread"))examples=examples.replace("multisig action:","multisigread action:");
                if(name.equals("mysql"))examples=examples.replace("archivexport-DDMMYY.gzip","archivexport-DDMMYY.raw.dat");
                if(Arrays.asList("archive","megammrsync","mysql").contains(name)) {
                    assertTrue(translated.contains("maxuses"));assertTrue(translated.contains("64x3"));
                    assertFalse(translated.contains("the max is 262144"));
                    if(code.equals("en"))assertTrue(translated.contains("do not assume the default"));
                }
                if(name.equals("mysqlcoins"))examples="mysqlcoins host:127.0.0.1:3306 database:coinsdb user:myuser password:myuser action:update maxcoins:100\n\nmysqlcoins (If you have not setlogin in mysql function)..LOGIN_DETAILS.. action:search where:\"address='0x791E78C60652B0E19B8FE9EB035B122B261490C477FD76E38C0C928187076103'\"\n\nmysqlcoins action:search address:0x791E78C60652B0E19B8FE9EB035B122B261490C477FD76E38C0C928187076103 spent:false limit:1\n\nmysqlcoins action:search address:Mx87DE..\n\nmysqlcoins action:search query:\"SELECT * FROM coins WHERE address='0x791E78C60652B0E19B8FE9EB035B122B261490C477FD76E38C0C928187076103' AND state LIKE '%0xFFEEDD%' LIMIT 10\"\n\nmysqlcoins action:autobackup enable:true\n\nmysqlcoins action:search address:Mx87DE.. spent:false limit:10 hidetoken:true";


                assertTrue(name,translated.endsWith(examples));
                if(name.equals("txninput"))assertFalse(translated.contains("The coin that is used"));
                if(name.equals("txnsign"))assertTrue(translated.contains("blockaskeyuses"));
                if(name.equals("reset")){assertTrue(translated.contains("maxuses"));assertFalse(translated.contains("the max is 262144"));}
                if(name.equals("restoresync"))assertTrue(translated.contains("256"));
                if(name.equals("coins"))assertTrue(translated.contains("{port:value}"));
                if(Arrays.asList("send","sendnosign","sendpoll","tokencreate").contains(name))assertTrue(translated.contains("{\"port\":\"value\",..}"));
                if(name.equals("tokens")) {
                    assertFalse(translated.contains("List your existing public keys"));assertFalse(translated.contains("Create a new key"));
                    if(code.equals("en")){assertTrue(translated.contains("import : Import token data supplied in data."));assertTrue(translated.contains("export : Export the token data identified by tokenid."));}
                }
                if(name.equals("txnscript")){assertTrue(translated.contains("{script:proof}"));assertTrue(translated.contains("{\"RETURN TRUE\":\"\"}"));}
                for(Map.Entry<String,ParamDocs.Doc> entry:docs.get(name).entrySet()) {
                    String description=entry.getValue().desc;
                    if(description.isEmpty())continue;
                    assertTrue(name+":"+entry.getKey(),HelpText.message(description) instanceof Language.Message);
                    if(code.equals("en") && !(Arrays.asList("txnsign","reset","restoresync","consolidatefrom","createtokenfrom","sendfrom","signfrom","archive","megammrsync","mysql").contains(name) && entry.getKey().equals("keyuses") || name.equals("tokens") && entry.getKey().equals("action") || name.equals("sphincs") && entry.getKey().equals("file")))assertEquals(description,HelpText.message(description).toString());
                    else assertNotEquals(name+":"+entry.getKey(),description,HelpText.message(description).toString());
                }
            }
            assertFalse(ParamDocs.forCommand(context,"status").get("clean").required);
            assertTrue(ParamDocs.forCommand(context,"coinimport").get("data").required);
            assertFalse(ParamDocs.forCommand(context,"coinimport").get("track").required);
            assertTrue(ParamDocs.forCommand(context,"txnsign").get("id").required);
            assertTrue(ParamDocs.forCommand(context,"txnsign").get("publickey").required);
            assertFalse(ParamDocs.forCommand(context,"txnsign").get("keyuses").required);
            assertTrue(ParamDocs.forCommand(context,"txninput").get("id").required);
            assertFalse(ParamDocs.forCommand(context,"txninput").get("coinid").required);
            assertFalse(ParamDocs.forCommand(context,"txnimport").get("id").required);
            String description=ParamDocs.forCommand(context,"coinimport").get("data").desc;
            if(code.equals("en"))assertEquals(description,HelpText.message(description).toString());else assertNotEquals(description,HelpText.message(description).toString());
            assertEquals(RAW,HelpText.message(RAW).toString());assertNull(HelpText.full(context,"unknown_fixture"));assertNull(HelpText.brief(context,"unknown_fixture"));
        }
    }
    @Test public void helpDialogAndSuggestionRowsSwitchWithoutReplacingDraftOrRows() throws Exception {
        AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<TextView> body=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<View> row=new AtomicReference<>();
            scenario.onActivity(a->{node.set(freeze(a));terminal(a).mInput.setText("sta");terminal(a).mInput.setSelection(3);row.set(terminal(a).mDropdown.getChildAt(0));});
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{assertSame(row.get(),terminal(a).mDropdown.getChildAt(0));assertEquals("sta",terminal(a).mInput.getText().toString());assertEquals(3,terminal(a).mInput.getSelectionStart());assertTrue(((TextView)row.get()).getText().toString().contains(HelpText.message(ParamDocs.commandBrief(a,"status")).toString()));});
            }
            scenario.onActivity(a->{row.get().performLongClick();retained.set(dialog(a));for(TextView view:texts(dialog(a).getWindow().getDecorView()))if(view.getText().toString().startsWith("status\n\n"))body.set(view);assertNotNull(body.get());assertTrue(body.get().isTextSelectable());});
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{assertSame(retained.get(),dialog(a));assertEquals(HelpText.full(a,"status").toString(),body.get().getText().toString());assertEquals("sta",terminal(a).mInput.getText().toString());assertTrue(node.get().callbacks.isEmpty());});
                screenshot("command-help-"+code);
            }
            scenario.onActivity(a->dialog(a).dismiss());settle();
            scenario.onActivity(a->{terminal(a).mInput.setText("coinimport ");terminal(a).mInput.setSelection(11);});
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{String description=HelpText.message(ParamDocs.forCommand(a,"coinimport").get("data").desc).toString();assertTrue(((TextView)terminal(a).mDropdown.getChildAt(0)).getText().toString().contains(description));assertEquals("coinimport ",terminal(a).mInput.getText().toString());});
            }
            scenario.onActivity(a->{terminal(a).mTabKey.performClick();assertEquals("coinimport data:",terminal(a).mInput.getText().toString());assertTrue(node.get().callbacks.isEmpty());});
            for(String page:new String[]{"peers","send","tokens","sendfrom","rawtxnfrom","sphincs","archive","mysql","multisigread"}) {
                scenario.onActivity(a->{
                    body.set(null);invoke(terminal(a),"showHelpDialog",new Class<?>[]{String.class},page);retained.set(dialog(a));
                    for(TextView view:texts(dialog(a).getWindow().getDecorView()))if(view.getText().toString().startsWith(page+"\n\n"))body.set(view);
                    assertNotNull(body.get());assertTrue(body.get().isTextSelectable());
                });
                for(String code:CODES) {
                    scenario.onActivity(a->{Language.select(a,code);((android.widget.ScrollView)body.get().getParent()).scrollTo(0,0);});settle();
                    scenario.onActivity(a->{assertSame(retained.get(),dialog(a));assertEquals(HelpText.full(a,page).toString(),body.get().getText().toString());assertEquals("coinimport data:",terminal(a).mInput.getText().toString());assertTrue(node.get().callbacks.isEmpty());});
                    screenshot(page+"-help-top-"+code);
                    scenario.onActivity(a->((android.widget.ScrollView)body.get().getParent()).fullScroll(View.FOCUS_DOWN));settle();
                    scenario.onActivity(a->assertFalse(((android.widget.ScrollView)body.get().getParent()).canScrollVertically(1)));
                    screenshot(page+"-help-end-"+code);
                }
                if(!page.equals("multisigread")){scenario.onActivity(a->dialog(a).dismiss());settle();}
            }
        }
        assertFalse(retained.get().isShowing());
    }
    @Test public void completedHelpUsesCapturedPayloadAndUnknownOrFailedRepliesStayLiteral() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<FixtureNode> node=new AtomicReference<>();List<TerminalLabel> outputs=new ArrayList<>();List<String> rawExpected=new ArrayList<>();
            scenario.onActivity(a->{
                node.set(freeze(a));TerminalView terminal=terminal(a);
                try {
                    JSONObject known=new JSONObject().put("status",true).put("response",new JSONObject().put("command","status").put("fullhelp",RAW));
                    terminal.runCMD("help command:status");node.get().reply("help command:status",known);outputs.add((TerminalLabel)terminal.mOutput.getChildAt(2));
                    known.getJSONObject("response").put("command","mutated_after_callback");
                    JSONObject listing=new JSONObject().put("status",true).put("response",new JSONObject().put("status","node summary").put("unknown_fixture",RAW));
                    terminal.runCMD("help");node.get().reply("help",listing);outputs.add((TerminalLabel)terminal.mOutput.getChildAt(4));listing.getJSONObject("response").put("unknown_fixture","mutated_after_callback");
                    String[] commands={"helpful_fixture", "help;status", "help command:status"};
                    for(int i=0;i<commands.length;i++) {
                        JSONObject reply=new JSONObject().put("status",i!=2).put("error",RAW).put("response",new JSONObject().put("command","status").put("fullhelp",RAW));
                        rawExpected.add(reply.toString(2));terminal.runCMD(commands[i]);node.get().reply(commands[i],reply);outputs.add((TerminalLabel)terminal.mOutput.getChildAt(6+i*2));
                    }
                    JSONObject unknown=new JSONObject().put("status",true).put("response",new JSONObject().put("command","unknown_fixture").put("help", "Show details about the transaction").put("fullhelp",RAW));
                    terminal.runCMD("help command:unknown_fixture");node.get().reply("help command:unknown_fixture",unknown);outputs.add((TerminalLabel)terminal.mOutput.getChildAt(12));
                    unknown.getJSONObject("response").put("fullhelp","mutated_after_callback");
                } catch(Exception e){throw new AssertionError(e);}
            });
            for(String code:CODES) {
                scenario.onActivity(a->Language.select(a,code));settle();
                scenario.onActivity(a->{
                    assertTrue(outputs.get(0).getText().toString().contains(HelpText.full(a,"status")));
                    assertTrue(outputs.get(1).getText().toString().contains(HelpText.brief(a,"status")));
                    assertTrue(outputs.get(1).getText().toString().contains(RAW));
                    for(int i=0;i<3;i++)assertTrue(outputs.get(i+2).getText().toString().startsWith(rawExpected.get(i)+"\n"));
                    assertTrue(outputs.get(5).getText().toString().startsWith("unknown_fixture  Show details about the transaction\n\n"+RAW));
                    for(TerminalLabel output:outputs){assertFalse(output.getText().toString().contains("mutated_after_callback"));assertTrue(output.getText() instanceof Spanned);}
                    assertTrue(node.get().callbacks.isEmpty());assertTrue(terminal(a).exportText().contains(RAW));
                });
                screenshot("command-help-output-"+code);
            }
        }
    }

    @Test public void longSigningHelpRetainsDialogAndDraftAndReachesExamples() throws Exception {
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        AtomicReference<TextView> body=new AtomicReference<>();
        AtomicReference<android.widget.ScrollView> scroller=new AtomicReference<>();
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                node.set(freeze(a));terminal(a).mInput.setText("txnsign id:literal_fixture publickey:auto");terminal(a).mInput.setSelection(11);
                invoke(terminal(a),"showHelpDialog",new Class<?>[]{String.class},"txnsign");retained.set(dialog(a));
                for(TextView view:texts(dialog(a).getWindow().getDecorView()))if(view.getText().toString().startsWith("txnsign\n\n"))body.set(view);
                assertNotNull(body.get());assertTrue(body.get().isTextSelectable());
                scroller.set((android.widget.ScrollView)body.get().getParent());
            });
            for(String code:CODES) {
                scenario.onActivity(a->{Language.select(a,code);scroller.get().scrollTo(0,0);});settle();
                scenario.onActivity(a->{
                    assertSame(retained.get(),dialog(a));assertEquals(HelpText.full(a,"txnsign").toString(),body.get().getText().toString());
                    assertEquals("txnsign id:literal_fixture publickey:auto",terminal(a).mInput.getText().toString());
                    assertEquals(11,terminal(a).mInput.getSelectionStart());assertTrue(node.get().callbacks.isEmpty());
                    assertTrue(scroller.get().canScrollVertically(1));
                });
                screenshot("transaction-help-top-"+code);
                scenario.onActivity(a->scroller.get().fullScroll(View.FOCUS_DOWN));settle();
                scenario.onActivity(a->{assertFalse(scroller.get().canScrollVertically(1));assertTrue(scroller.get().canScrollVertically(-1));});
                screenshot("transaction-help-end-"+code);
            }
        }
        assertFalse(retained.get().isShowing());
    }
    @Test public void longVaultHelpRetainsWarningAndDraftAndReachesExamples() throws Exception {
        AtomicReference<AlertDialog> retained=new AtomicReference<>();
        AtomicReference<TextView> body=new AtomicReference<>();
        AtomicReference<android.widget.ScrollView> scroller=new AtomicReference<>();
        AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                node.set(freeze(a));terminal(a).mInput.setText("txnsign id:literal_fixture publickey:auto");terminal(a).mInput.setSelection(11);
                invoke(terminal(a),"showHelpDialog",new Class<?>[]{String.class},"vault");retained.set(dialog(a));
                for(TextView view:texts(dialog(a).getWindow().getDecorView()))if(view.getText().toString().startsWith("vault\n\n"))body.set(view);
                assertNotNull(body.get());assertTrue(body.get().isTextSelectable());
                scroller.set((android.widget.ScrollView)body.get().getParent());
            });
            for(String code:CODES) {
                scenario.onActivity(a->{Language.select(a,code);scroller.get().scrollTo(0,0);});settle();
                scenario.onActivity(a->{
                    assertSame(retained.get(),dialog(a));assertEquals(HelpText.full(a,"vault").toString(),body.get().getText().toString());
                    assertEquals("txnsign id:literal_fixture publickey:auto",terminal(a).mInput.getText().toString());
                    assertEquals(11,terminal(a).mInput.getSelectionStart());assertTrue(node.get().callbacks.isEmpty());
                    assertTrue(scroller.get().canScrollVertically(1));
                });
                screenshot("wallet-help-top-"+code);
                scenario.onActivity(a->scroller.get().fullScroll(View.FOCUS_DOWN));settle();
                scenario.onActivity(a->{assertFalse(scroller.get().canScrollVertically(1));assertTrue(scroller.get().canScrollVertically(-1));});
                screenshot("wallet-help-end-"+code);
            }
        }
        assertFalse(retained.get().isShowing());
    }}
