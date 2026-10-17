package com.eurobuddha.terminalide.ide;

import android.app.Instrumentation;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.terminalide.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.terminalide.ide.EditorLanguagesTest.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Reuse the guarded editor fixture; never starts a node or deploys a script. */
@RunWith(AndroidJUnit4.class)
public class ReferenceLanguagesTest {
    final EditorLanguagesTest fixture = new EditorLanguagesTest();
    @Before public void prepare() { fixture.prepare(); }
    @After public void reset() { fixture.reset(); }
    static String flat(String value) { return value.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("\\s+", " ").trim(); }
    static ScriptDB.Script newest(ScriptDB db) { return Collections.max(db.getAll(), Comparator.comparingLong(s -> s.id)); }

    @Test public void referenceSwitchesInPlaceAndKeepsDraftAndLiteralSyntax() throws Exception {
        AtomicReference<AlertDialog> retained = new AtomicReference<>();
        AtomicReference<TextView> body = new AtomicReference<>();
        AtomicReference<FixtureNode> node = new AtomicReference<>();
        try (ActivityScenario<ScriptEditorActivity> scenario = fixture.editor()) {
            scenario.onActivity(a -> {
                node.set(freeze(a));a.mEditor.setSelection(8,12);
                invoke(a,"showKissReference",new Class<?>[]{});
                retained.set(dialog(a));
                for(TextView text:texts(dialog(a).getWindow().getDecorView()))
                    if(text.getText().toString().contains("LET myvar = 5")) body.set(text);
                assertNotNull(body.get());assertTrue(body.get().isTextSelectable());
            });
            for(String code:CODES) {
                scenario.onActivity(a -> Language.select(a,code));fixture.settle();
                scenario.onActivity(a -> {
                    assertSame(retained.get(),dialog(a));
                    assertEquals(Language.text(a,l10n_terminal_reference_body),body.get().getText().toString());
                    assertTrue(body.get().getText().toString().contains(((TextView)a.findViewById(R.id.pm_terminalide_editor_run)).getText().toString()));
                    assertEquals(SOURCE,a.mEditor.getText().toString());assertEquals(8,a.mEditor.getSelectionStart());assertEquals(12,a.mEditor.getSelectionEnd());
                    assertEquals(RAW,a.mName.getText().toString());assertTrue(node.get().callbacks.isEmpty());
                    for(String literal:new String[]{"LET myvar = 5", "LET hpk = PREVSTATE(0)",
                        "IF cond THEN ... ELSEIF cond THEN ... ELSE ... ENDIF", "WHILE cond DO ... ENDWHILE",
                        "VERIFYOUT(idx address amount tokenid keepstate)", "VERIFYIN(idx address amount tokenid)",
                        "GETOUTADDR/GETOUTAMT/GETOUTTOK/GETOUTKEEPSTATE(idx)", "GETINADDR/GETINAMT/GETINTOK/GETINID(idx)",
                        "SUMINPUTS(tokenid)  SUMOUTPUTS(tokenid)", "0xAA+0xBB -> 0xAABB",
                        "LET r = NUMBER(SUBSET(0 4 SHA3(CONCAT(s1 s2)))) % range", "@INPUT @AMOUNT @ADDRESS @TOKENID @COINID",
                        "@TOTIN @TOTOUT @SCRIPT @FLOATING @CREATED @BLOCK", "EQ NEQ GT GTE LT LTE",
                        "AND OR XOR NAND NOR NXOR NOT", "VERIFYOUT(... TRUE)", "storestate:true", "storestate:false"})
                        assertTrue(literal,body.get().getText().toString().contains(literal));
                    ((ScrollView)body.get().getParent()).scrollTo(0,0);
                });
                fixture.screenshot("reference-top-"+code);
                scenario.onActivity(a -> ((ScrollView)body.get().getParent()).fullScroll(android.view.View.FOCUS_DOWN));
                fixture.screenshot("reference-end-"+code);
            }
        }
        assertFalse(retained.get().isShowing());
    }

    @Test public void newTemplatesCaptureChosenLanguageButPreserveExecutableCode() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(ScriptEditorActivity.class.getName(),new Instrumentation.ActivityResult(0,null),true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<ScriptsView> library=new AtomicReference<>();
            scenario.onActivity(a -> {
                ((NodeApi)field(a,"mNode")).onDestroy();
                library.set(((TerminalAdapter)field(a,"mAdapter")).getScriptsView());
                library.get().setNodeApi(new FixtureNode(a));
            });
            Map<Long,String[]> saved=new LinkedHashMap<>();
            int[] names={l10n_terminal_template_untitled,l10n_terminal_editor_signature,l10n_terminal_editor_timelock,
                l10n_terminal_editor_hashlock,l10n_terminal_editor_multisig,l10n_terminal_editor_random,l10n_terminal_editor_covenant};
            for(String code:CODES) {
                scenario.onActivity(a -> Language.select(a,code));fixture.settle();
                for(int index=0;index<Templates.ALL.length;index++) {
                    final int template=index;
                    scenario.onActivity(a -> invoke(library.get(),"nameDialog",new Class<?>[]{int.class},template));fixture.settle();
                    scenario.onActivity(a -> dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());fixture.settle();
                    scenario.onActivity(a -> {
                        ScriptDB.Script item=newest(library.get().mDB);
                        assertEquals(Language.text(a,names[template]),item.name);
                        assertEquals(flat(Templates.ALL[template][1]),flat(item.source));
                        if(code.equals("en"))assertEquals(Templates.ALL[template][1],item.source);
                        else if(template!=0)assertNotEquals(Templates.ALL[template][1],item.source);
                        saved.put(item.id,new String[]{item.name,item.source});
                        for(Map.Entry<Long,String[]> entry:saved.entrySet()) {
                            ScriptDB.Script old=library.get().mDB.get(entry.getKey());
                            assertEquals(entry.getValue()[0],old.name);assertEquals(entry.getValue()[1],old.source);
                        }
                    });
                }
            }
            assertEquals(28,saved.size());assertEquals(28,monitor.getHits());
        } finally { instrumentation.removeMonitor(monitor); }
    }

    @Test public void userNamesCopiesAndImportedSourceRemainLiteral() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(ScriptEditorActivity.class.getName(),new Instrumentation.ActivityResult(0,null),true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<ScriptsView> library=new AtomicReference<>();
            scenario.onActivity(a -> {((NodeApi)field(a,"mNode")).onDestroy();library.set(((TerminalAdapter)field(a,"mAdapter")).getScriptsView());library.get().setNodeApi(new FixtureNode(a));});
            for(String code:CODES) {
                scenario.onActivity(a -> {Language.select(a,code);invoke(library.get(),"nameDialog",new Class<?>[]{int.class},1);});fixture.settle();
                scenario.onActivity(a -> {for(TextView text:texts(dialog(a).getWindow().getDecorView()))if(text instanceof EditText)text.setText(RAW);dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick();});fixture.settle();
                scenario.onActivity(a -> {
                    assertEquals(RAW,newest(library.get().mDB).name);
                    invoke(library.get(),"scriptActionsDialog",new Class<?>[]{ScriptDB.Script.class},library.get().mDB.get(fixture.id));
                });fixture.settle();
                scenario.onActivity(a -> {ListView list=dialog(a).getListView();list.performItemClick(list.getChildAt(1),1,1);});fixture.settle();
                scenario.onActivity(a -> {
                    ScriptDB.Script copy=newest(library.get().mDB);assertEquals(Language.text(a,l10n_terminal_template_copy,RAW),copy.name);assertEquals(SOURCE,copy.source);
                    invoke(library.get(),"nodeScriptActions",new Class<?>[]{String.class,String.class},"0x001234",SOURCE);
                });fixture.settle();
                scenario.onActivity(a -> dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick());fixture.settle();
                scenario.onActivity(a -> {
                    ScriptDB.Script imported=newest(library.get().mDB);assertEquals(Language.text(a,l10n_terminal_template_from_node,"0x001234"),imported.name);assertEquals(SOURCE,imported.source);assertEquals("0x001234",imported.address);
                    ScriptDB.Script original=library.get().mDB.get(fixture.id);assertEquals(RAW,original.name);assertEquals(SOURCE,original.source);
                });
            }
        } finally { instrumentation.removeMonitor(monitor); }
    }
}
