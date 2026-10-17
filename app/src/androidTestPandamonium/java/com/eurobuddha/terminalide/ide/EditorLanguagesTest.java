package com.eurobuddha.terminalide.ide;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import com.eurobuddha.terminalide.*;
import com.eurobuddha.terminalide.terminal.OutputFormatter;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Public fixtures only; all commands are captured and never reach an embedded node. */
@RunWith(AndroidJUnit4.class)
public class EditorLanguagesTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    static final String[] CODES={"zh-Hans","ru","uk","en"};
    static final String RAW="中文 Русский Українська {value} 00012.34000";
    static final String SOURCE="/* literal 中文 */\nLET value = 123\nRETURN TRUE";
    long id;
    @Before public void prepare(){
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        context.deleteDatabase(ScriptDB.DATABASE_NAME);
        try(ScriptDB db=new ScriptDB(context)){id=db.insert(RAW,SOURCE);db.saveTestInputs(id,"{\"0\":\"0xFF\"}","{\"6\":\"0\"}","{\"@COINAGE\":\"64\"}","[\"0xFF\"]");}
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset(){
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
    }
    ActivityScenario<ScriptEditorActivity> editor(){return ActivityScenario.launch(new Intent(context,ScriptEditorActivity.class).putExtra("script_id",id));}
    static class FixtureNode extends NodeApi {
        final Map<String,Cb> callbacks=new LinkedHashMap<>();
        FixtureNode(Context c){super(c,null);}
        @Override public void cmd(String s,Cb cb){callbacks.put(s,cb);}
        void reply(JSONObject reply){assertEquals(1,callbacks.size());Cb cb=callbacks.values().iterator().next();callbacks.clear();cb.onResult(reply);}
    }
    static FixtureNode freeze(ScriptEditorActivity a){a.mNode.onDestroy();FixtureNode n=new FixtureNode(a);a.mNode=n;return n;}
    static Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    static void invoke(Object target,String name,Class<?>[] types,Object...args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") static Set<AlertDialog> dialogs(Object a){return (Set<AlertDialog>)field(a,"languageDialogs");}
    static AlertDialog dialog(Object a){assertEquals(1,dialogs(a).size());return dialogs(a).iterator().next();}
    static List<TextView> texts(View v){List<TextView> out=new ArrayList<>();if(v instanceof TextView)out.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)out.addAll(texts(((ViewGroup)v).getChildAt(i)));return out;}
    static void has(View root,String value){for(TextView t:texts(root))if(t.getText().toString().equals(value))return;throw new AssertionError("Missing "+value);}
    static void has(View root,Context c,int res,Object...args){has(root,Language.text(c,res,args));}
    void settle(){android.os.SystemClock.sleep(350);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    void screenshot(String name)throws Exception{
        settle();java.io.File dir=new java.io.File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());
        android.graphics.Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(b);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){assertTrue(b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
    }
    static JSONObject json(String value){try{return new JSONObject(value);}catch(Exception e){throw new AssertionError(e);}}
    static JSONObject result(){
        try{return new JSONObject().put("status",true).put("response",new JSONObject().put("parseok",true).put("success",true).put("monotonic",false).put("clean",new JSONObject().put("address","0x001234").put("script",SOURCE)).put("variables",new JSONObject().put("value",RAW)).put("trace",RAW+"\\nRETURN TRUE"));}catch(Exception e){throw new AssertionError(e);}
    }
    @Test public void draftsInputsHighlightAndLateRunKeepTheirIdentity()throws Exception{
        AtomicReference<ScriptEditorActivity> original=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<ScriptEditorActivity> s=editor()){
            s.onActivity(a->{original.set(a);node.set(freeze(a));a.mName.setSelection(4);a.mEditor.setSelection(10,15);a.mState.setSelection(3);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(original.get(),a);assertEquals(RAW,a.mName.getText().toString());assertEquals(4,a.mName.getSelectionStart());
                assertEquals(SOURCE,a.mEditor.getText().toString());assertEquals(10,a.mEditor.getSelectionStart());assertEquals(15,a.mEditor.getSelectionEnd());assertEquals(3,a.mState.getSelectionStart());
                Spanned spans=a.mEditor.getText();assertTrue(spans.getSpans(0,spans.length(),ForegroundColorSpan.class).length>=4);
                assertEquals(View.VISIBLE,a.mRunConfig.getVisibility());has(a.getWindow().getDecorView(),a,l10n_terminal_editor_inputs_open);
                assertEquals(Language.text(a,l10n_terminal_editor_name),a.mName.getHint().toString());
                assertTrue(a.mState.getHint().toString().endsWith("{\"0\":\"0xFF..\",\"6\":\"1\"}"));
                Toolbar toolbar=a.findViewById(R.id.pm_terminalide_editor_toolbar);assertEquals(Language.text(a,l10n_terminal_editor_title),toolbar.getTitle().toString());assertEquals(Language.text(a,l10n_terminal_editor_reference),toolbar.getMenu().findItem(1).getTitle().toString());
                assertTrue(a.mStatusLine.getText().toString().contains(Language.text(a,l10n_terminal_editor_no_issues)));
            });screenshot("editor-inputs-"+code);}
            s.onActivity(a->a.findViewById(R.id.pm_terminalide_editor_run).performClick());
            s.onActivity(a->{assertEquals("runscript script:\"LET value = 123 RETURN TRUE\" state:{\"0\":\"0xFF\"} prevstate:{\"6\":\"0\"} globals:{\"@COINAGE\":\"64\"} signatures:[\"0xFF\"]",node.get().callbacks.keySet().iterator().next());Language.select(a,"uk");});settle();
            s.onActivity(a->{assertEquals(Language.text(a,l10n_terminal_editor_running),a.mResults.getText().toString());node.get().reply(result());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                String shown=a.mResults.getText().toString();assertTrue(shown.contains(SOURCE));assertTrue(shown.contains(RAW));assertTrue(shown.contains("0x001234"));assertTrue(shown.contains(Language.text(a,l10n_terminal_editor_spendable)));assertTrue(shown.contains(Language.text(a,l10n_terminal_editor_monotonic,false)));assertEquals(OutputFormatter.COL_STRING,a.mResults.getCurrentTextColor());
            });}
        }
        try(ScriptDB db=new ScriptDB(context)){ScriptDB.Script saved=db.get(id);assertEquals(RAW,saved.name);assertEquals(SOURCE,saved.source);assertEquals("{\"0\":\"0xFF\"}",saved.testState);assertEquals("[\"0xFF\"]",saved.testSignatures);}
    }
    @Test public void lintDialogAndLocalValidationPreserveRawDiagnostics()throws Exception{
        AtomicReference<AlertDialog> retained=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
        try(ActivityScenario<ScriptEditorActivity> s=editor()){
            s.onActivity(a->{node.set(freeze(a));a.mEditor.setText("LET bad_name = NUMBER(SHA3(0xFF))\nIF @BLKNUM GT 0 THEN\nRETURN TRUE");a.mStatusLine.performClick();retained.set(dialog(a));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(retained.get(),dialog(a));String body=((TextView)dialog(a).findViewById(android.R.id.message)).getText().toString();
                assertTrue(body.contains("bad_name"));assertTrue(body.contains("badname"));assertTrue(body.contains("@BLKNUM"));assertTrue(body.contains("@COINAGE"));assertTrue(body.contains("NUMBER(SUBSET(0 4 SHA3(...)))"));assertTrue(body.contains("ENDIF"));
                for(LintEngine.Lint lint:a.mLints)assertTrue(body.contains(EditorMessages.message(lint.message)));
            });screenshot("editor-lints-"+code);}
            s.onActivity(a->{dialog(a).dismiss();a.mEditor.setText("RETURN TRUE");a.mState.setText("{\"0\":\"x;status\"}");a.findViewById(R.id.pm_terminalide_editor_run).performClick();assertTrue(node.get().callbacks.isEmpty());});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertTrue(a.mResults.getText().toString().contains(Language.text(a,l10n_terminal_editor_unsafe,"state")));assertEquals(OutputFormatter.COL_ERROR,a.mResults.getCurrentTextColor());});}
            s.onActivity(a->{a.mState.setText("{");a.findViewById(R.id.pm_terminalide_editor_run).performClick();assertTrue(node.get().callbacks.isEmpty());assertTrue(a.mResults.getText().toString().contains("state"));
                invoke(a,"showCheckResult",new Class<?>[]{JSONObject.class,boolean.class},json("{\"status\":false,\"error\":\"No RETURN statement — a script must RETURN TRUE to be spendable.\"}"),false);
            });
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals("✖ No RETURN statement — a script must RETURN TRUE to be spendable.",a.mResults.getText().toString()));}
            s.onActivity(a->{invoke(a,"showLints",new Class<?>[]{});a.mEditor.setText("LET broken_name = 1");a.mStatusLine.performClick();retained.set(dialog(a));});
        }
        assertFalse(retained.get().isShowing());
    }
    @Test public void deploymentDialogKeepsCheckboxAndCapturedScript()throws Exception{
        AtomicReference<FixtureNode> node=new AtomicReference<>();AtomicReference<AlertDialog> retained=new AtomicReference<>();
        try(ActivityScenario<ScriptEditorActivity> s=editor()){
            s.onActivity(a->{node.set(freeze(a));a.findViewById(R.id.pm_terminalide_editor_deploy).performClick();retained.set(dialog(a));CheckBox check=dialog(a).findViewById(R.id.pm_terminalide_deploy_trackall);assertFalse(check.isChecked());check.setChecked(true);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{
                assertSame(retained.get(),dialog(a));CheckBox check=dialog(a).findViewById(R.id.pm_terminalide_deploy_trackall);assertTrue(check.isChecked());assertEquals(Language.text(a,l10n_terminal_editor_trackall),check.getText().toString());assertTrue(node.get().callbacks.isEmpty());has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_editor_deploy_body);
            });screenshot("editor-deploy-"+code);}
            s.onActivity(a->{a.mEditor.setText("RETURN FALSE");dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});settle();
            s.onActivity(a->{assertEquals("newscript trackall:true script:\"LET value = 123 RETURN TRUE\"",node.get().callbacks.keySet().iterator().next());node.get().reply(json("{\"status\":true,\"response\":{\"address\":\"0x001234\"}}"));assertEquals("0x001234",a.mDB.get(id).address);});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_editor_deployed,"0x001234","0x001234"),a.mResults.getText().toString()));}
        }
    }
    @Test public void libraryRowsTemplateChoicesAndDeleteDialogKeepStoredCode()throws Exception{
        try(ActivityScenario<MainActivity> s=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<ScriptsView> library=new AtomicReference<>();AtomicReference<ListAdapter> adapter=new AtomicReference<>();AtomicReference<FixtureNode> node=new AtomicReference<>();
            s.onActivity(a->{((NodeApi)field(a,"mNode")).onDestroy();ScriptsView v=((TerminalAdapter)field(a,"mAdapter")).getScriptsView();library.set(v);node.set(new FixtureNode(a));v.setNodeApi(node.get());((androidx.viewpager.widget.ViewPager)a.findViewById(R.id.pm_terminalide_main_pager)).setCurrentItem(1);});settle();s.onActivity(a->adapter.set(library.get().mList.getAdapter()));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{ScriptsView v=library.get();assertSame(adapter.get(),v.mList.getAdapter());String row=((TextView)v.mList.getChildAt(0)).getText().toString();assertTrue(row.startsWith(RAW+"\n"));assertTrue(row.contains(Language.text(a,l10n_terminal_editor_chars,SOURCE.trim().length())));assertEquals(SOURCE,v.mDB.get(id).source);});}
            s.onActivity(a->invoke(library.get(),"newScriptDialog",new Class<?>[]{}));
            int[] names={l10n_terminal_editor_blank,l10n_terminal_editor_signature,l10n_terminal_editor_timelock,l10n_terminal_editor_hashlock,l10n_terminal_editor_multisig,l10n_terminal_editor_random,l10n_terminal_editor_covenant};
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{ListAdapter choices=dialog(a).getListView().getAdapter();assertEquals(7,choices.getCount());for(int i=0;i<7;i++)assertEquals(Language.text(a,names[i]),choices.getItem(i).toString());});}
            s.onActivity(a->dialog(a).dismiss());settle();
            s.onActivity(a->{invoke(library.get(),"scriptActionsDialog",new Class<?>[]{ScriptDB.Script.class},library.get().mDB.get(id));AlertDialog d=dialog(a);d.getListView().performItemClick(d.getListView().getChildAt(2),2,2);});settle();
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_editor_delete_title,RAW);has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_editor_delete_body);assertEquals(SOURCE,library.get().mDB.get(id).source);});}
            s.onActivity(a->dialog(a).getButton(AlertDialog.BUTTON_NEGATIVE).performClick());settle();
            s.onActivity(a->{invoke(library.get(),"showNodeScripts",new Class<?>[]{});assertEquals(Collections.singleton("scripts"),node.get().callbacks.keySet());node.get().reply(json("{\"status\":true,\"response\":[{\"default\":true,\"address\":\"0x001234\",\"script\":\"RETURN TRUE\"},null]}"));});
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{ListAdapter choices=dialog(a).getListView().getAdapter();assertEquals(Language.text(a,l10n_terminal_editor_wallet)+"0x001234\nRETURN TRUE",choices.getItem(0).toString());assertEquals(Language.text(a,l10n_terminal_editor_unreadable),choices.getItem(1).toString());});}
        }
    }
    @Test public void referenceMenuOpensAndFallbackResultsRefresh()throws Exception{
        try(ActivityScenario<ScriptEditorActivity> s=editor()){
            s.onActivity(a->freeze(a));settle();
            s.onActivity(a->{Toolbar toolbar=a.findViewById(R.id.pm_terminalide_editor_toolbar);assertTrue(toolbar.getMenu().performIdentifierAction(1,0));});settle();
            AtomicReference<AlertDialog> retained=new AtomicReference<>();s.onActivity(a->retained.set(dialog(a)));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->{assertSame(retained.get(),dialog(a));has(dialog(a).getWindow().getDecorView(),a,l10n_terminal_editor_reference);assertEquals(Language.text(a,l10n_language_close),dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());});}
            s.onActivity(a->dialog(a).dismiss());settle();
            s.onActivity(a->invoke(a,"showCheckResult",new Class<?>[]{JSONObject.class,boolean.class},json("{\"status\":false}"),false));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals("✖ "+Language.text(a,l10n_terminal_editor_run_failed),a.mResults.getText().toString()));}
            s.onActivity(a->invoke(a,"showCheckResult",new Class<?>[]{JSONObject.class,boolean.class},json("{\"status\":true}"),false));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_terminal_editor_empty_response),a.mResults.getText().toString()));}
            s.onActivity(a->invoke(a,"showNodeError",new Class<?>[]{String.class},NodeApi.ERR_NOT_ENABLED));
            for(String code:CODES){s.onActivity(a->Language.select(a,code));settle();s.onActivity(a->assertEquals(Language.text(a,l10n_explorer_node_retry),a.mResults.getText().toString()));}
        }
    }
    @Test public void allLintBranchesMapWithoutChangingTheirEngineOrLiteralValues(){
        String[] scripts={"LET one_two = 1", "RETURN TRUE "+" ".repeat(1200)+"X", "RETURN TRUE "+" ".repeat(1050)+"X", "RETURN @BLKNUM GT 0", "RETURN NUMBER(SHA3(0xFF))", "IF TRUE THEN RETURN TRUE", "ENDIF RETURN TRUE", "WHILE TRUE DO RETURN TRUE", "ENDWHILE RETURN TRUE"};
        Set<String> seen=new HashSet<>();for(String source:scripts)for(LintEngine.Lint lint:LintEngine.lint(source))seen.add(lint.message);
        assertTrue(seen.size()>=10);
        for(String code:CODES){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,code));
            for(String raw:seen){CharSequence value=EditorMessages.message(raw);assertTrue(raw,value instanceof Language.Message);if(code.equals("en"))assertEquals(raw,value.toString());else assertNotEquals(raw,value.toString());}
            assertEquals(RAW,EditorMessages.message(RAW).toString());
        }
    }
}
