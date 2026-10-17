package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.lock.AppLock;
import com.eurobuddha.minimacore.service.MinimaService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Only public fixtures, with the node stopped, on the owned disposable emulator. */
@RunWith(AndroidJUnit4.class)
public class FutureCashLanguagesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String[] CODES={"en","zh-Hans","ru","uk"};
    @Before public void guard() {
        assertEquals("true",InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable"));
        assertEquals("ranchu",Build.HARDWARE);
        assertNull(MinimaService.minima);assertNull(org.minima.system.Main.getInstance());
        assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
        context.getSharedPreferences("futurecash_guardian",0).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{AppLock.disable(context);Language.select(context,"en");});
    }
    @After public void reset() {
        if(!"ranchu".equals(Build.HARDWARE)||!"true".equals(InstrumentationRegistry.getArguments().getString("bearLanguagesDisposable")))return;
        context.getSharedPreferences("futurecash_guardian",0).edit().clear().commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->Language.select(context,"en"));
        assertNull(MinimaService.minima);assertFalse(context.getSharedPreferences("main_prefs",0).contains("SEED"));
    }
    private static Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object call(MainActivity a,String name)throws Exception {Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(a);}
    private static String text(View v) {
        StringBuilder b=new StringBuilder();collect(v,b);return b.toString();
    }
    private static void collect(View v,StringBuilder b) {
        if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),b);
    }
    private static View exact(View v,CharSequence label) {
        if(v instanceof TextView && label.toString().contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=exact(((ViewGroup)v).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private void settle(ActivityScenario<MainActivity> scenario)throws Exception {
        android.os.SystemClock.sleep(1200);InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a->{try{((ExecutorService)field("worker").get(a)).shutdownNow();((AuditRunner)field("auditRunner").get(a)).onDestroy();field("auditRunning").set(a,false);}catch(Exception e){throw new AssertionError(e);}});
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void assertButtonBounds(View view) {
        if(view instanceof android.widget.Button && view.getHeight()>0 && view.getParent() instanceof LinearLayout) {
            View parent=(View)view.getParent();
            assertTrue(((TextView)view).getText()+" clipped at top",view.getTop()>=0);
            assertTrue(((TextView)view).getText()+" clipped at bottom",view.getBottom()<=parent.getHeight());
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertButtonBounds(((ViewGroup)view).getChildAt(i));
    }
    private void screenshot(String name)throws Exception {
        // Match the wallet visual tests: allow the renderer to submit the changed view.
        android.os.SystemClock.sleep(350);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File dir=new File(context.getExternalFilesDir(null),"language-tests");assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private static KeyAudit.Result fixture()throws Exception {
        Method method=DualKeyAuditScreenTest.class.getDeclaredMethod("result",boolean.class,int.class);method.setAccessible(true);
        return (KeyAudit.Result)method.invoke(null,false,12);
    }
    @Test public void bothFamiliesAndPartialWarningSwitchLiveWithoutChangingPublicEvidence()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);
            final View[] views=new View[3];final KeyAudit.Result result=fixture();
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.set(Cfg.AUDIT_AT,String.valueOf(System.currentTimeMillis()));cfg.set(Cfg.AUDIT_LAST_ERR,"");
                field("lastAudit").set(a,result);field("lastVerdict").set(a,new Audit.Verdict(false,System.currentTimeMillis(),Audit.Mode.PARTIAL));
                views[0]=(View)call(a,"auditCard");views[1]=(View)call(a,"keyTableCard");
                KeyAudit.Result unavailable=KeyAudit.join(java.util.Collections.singletonList(new KeyAudit.LocalKey("invalid",0,0)),
                        java.util.Collections.emptyList(),java.util.Collections.emptyList(),java.util.Collections.singletonList(null));
                field("lastAudit").set(a,unavailable);views[2]=(View)call(a,"keyTableCard");field("lastAudit").set(a,result);
                LinearLayout content=(LinearLayout)field("content").get(a);content.removeAllViews();content.addView(views[0]);content.addView(views[1]);
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES) {
                scenario.onActivity(a->{try {
                    Language.select(a,code);
                    String summary=text(views[0]),detail=text(views[1]);
                    assertTrue(summary,summary.contains(Language.msg(l10n_future_incomplete_audit).toString()));
                    assertFalse(summary,summary.contains(Language.msg(l10n_future_clear_audit).toString()));
                    assertTrue(detail,detail.contains(Language.msg(l10n_future_key_families,1,1).toString()));
                    assertTrue(detail,detail.contains(Language.msg(l10n_future_classic_counter,12).toString()));
                    assertTrue(detail,detail.contains(Language.msg(l10n_future_block_position,2350000).toString()));
                    assertTrue(detail,detail.contains(Language.msg(l10n_future_not_checked).toString()));
                    for(KeyAudit.Row row:result.rows)assertTrue(detail,detail.contains(row.address));
                    assertSame(result,field("lastAudit").get(a));assertFalse(result.complete);assertFalse(result.exhaustion);
                    assertNotNull(exact(views[2],Language.msg(l10n_future_address_unavailable)));
                    assertNotNull(exact(views[2],Language.msg(l10n_future_not_checked)));
                    assertNotNull(exact(views[0],Language.msg(l10n_future_reaudit)));
                }catch(Exception e){throw new AssertionError(e);}});
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->assertButtonBounds(a.getWindow().getDecorView()));
                screenshot("future-audit-"+code);
            }
        }
    }
    @Test public void paymentDraftAndDelaySurviveLanguageStatusThemeAndTabChanges()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final EditText[] inputs=new EditText[2];
            scenario.onActivity(a->{try {
                field("tab").set(a,"lock");call(a,"buildTabs");call(a,"render");
                inputs[0]=(EditText)field("lockAmount").get(a);inputs[1]=(EditText)field("lockRecipient").get(a);
                inputs[0].setText("12.345600");inputs[0].setSelection(2,5);inputs[1].setText("MxPUBLIC-draft-Україна-中文");inputs[1].setSelection(3);
                View week=exact((View)field("content").get(a),Language.join("+",Language.msg(l10n_future_week)));assertNotNull(week);week.performClick();
                assertEquals(12096L,field("lockBlocks").getLong(a));
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES) {
                scenario.onActivity(a->{try {
                    Language.select(a,code);assertDraft(a,inputs);
                    call(a,"render");assertDraft(a,inputs); // The same redraw performed after node status updates.
                    field("tab").set(a,"help");call(a,"render");field("tab").set(a,"lock");call(a,"render");assertDraft(a,inputs);
                    a.findViewById(R.id.pm_futurecash_next_themeToggle).performClick();assertDraft(a,inputs);
                    assertEquals(Language.msg(l10n_future_amount).toString(),inputs[0].getHint().toString());
                    assertEquals(Language.msg(l10n_future_recipient).toString(),inputs[1].getHint().toString());
                    assertNotNull(exact((View)field("content").get(a),Language.msg(l10n_future_unlock_preview,Language.msg(l10n_future_week),12096L)));
                    assertNotNull(exact((View)field("content").get(a),Language.msg(l10n_future_lock)));
                }catch(Exception e){throw new AssertionError(e);}});
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->assertButtonBounds(a.getWindow().getDecorView()));
                screenshot("future-payment-"+code);
            }
        }
    }
    @Test public void successfulPaymentClearsOnlyTheUneditedSubmittedDraft()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);
            scenario.onActivity(a->{try {
                field("tab").set(a,"lock");call(a,"render");
                EditText amount=(EditText)field("lockAmount").get(a),recipient=(EditText)field("lockRecipient").get(a);
                amount.setText("12.345600");recipient.setText("MxPUBLIC");field("lockBlocks").setLong(a,12096);
                Method completed=MainActivity.class.getDeclaredMethod("clearSubmittedDraft",String.class,String.class,long.class);completed.setAccessible(true);
                completed.invoke(a,"12.345600","MxPUBLIC",1728L); // Different delay: preserve the new draft.
                assertEquals("12.345600",amount.getText().toString());assertEquals("MxPUBLIC",recipient.getText().toString());
                completed.invoke(a,"12","MxPUBLIC",12096L); // Different amount.
                assertEquals("12.345600",amount.getText().toString());
                completed.invoke(a,"12.345600","MxOTHER",12096L); // Different recipient.
                assertEquals("MxPUBLIC",recipient.getText().toString());
                completed.invoke(a,"12.345600","MxPUBLIC",12096L);
                assertEquals("",amount.getText().toString());assertEquals("",recipient.getText().toString());
                assertEquals(12096L,field("lockBlocks").getLong(a));
            }catch(Exception e){throw new AssertionError(e);}});
        }
    }

    @Test public void stakeLabelsSwitchWithoutChangingPayoutsOrOfferingRiskyCollection()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);
            Guardian.Stake safe=new Guardian.Stake();safe.coinid="0xSAFE";safe.payout="MxSAFE-public";safe.amount="2.25";safe.tokenid="0x00";safe.matureBlock=100;safe.ready=true;
            Guardian.Stake risk=new Guardian.Stake();risk.coinid="0xRISK";risk.payout="MxRISK-public";risk.amount="3.5";risk.tokenid="0x00";risk.matureBlock=99;risk.ready=true;risk.atRisk=true;
            Guardian.Stake pending=new Guardian.Stake();pending.coinid="0xPENDING";pending.payout="MxPENDING-public";pending.amount="4.75";pending.tokenid="0xTOKEN";pending.matureBlock=172;
            scenario.onActivity(a->{try{field("tip").setLong(a,100);field("stakes").set(a,java.util.Arrays.asList(safe,risk,pending));field("tab").set(a,"lock");call(a,"buildTabs");call(a,"render");}catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{try {
                Language.select(a,code);View content=(View)field("content").get(a);String shown=text(content);
                assertTrue(shown,shown.contains(Language.msg(l10n_future_ready_count,2).toString()));
                assertTrue(shown,shown.contains(Language.msg(l10n_future_reused_payout).toString()));
                assertTrue(shown,shown.contains(Language.msg(l10n_future_unlocks,Language.msg(l10n_future_in_minutes,60L)).toString()));
                for(Guardian.Stake stake:java.util.Arrays.asList(safe,risk,pending))assertTrue(shown,shown.contains(stake.payout));
                assertEquals(1,count(content,Language.msg(l10n_future_collect).toString()));
                assertTrue(safe.ready);assertTrue(risk.atRisk);assertFalse(pending.ready);assertEquals(172,pending.matureBlock);
            }catch(Exception e){throw new AssertionError(e);}});
        }
    }
    private static int count(View view,String label) {
        int n=view instanceof TextView && label.contentEquals(((TextView)view).getText())?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)n+=count(((ViewGroup)view).getChildAt(i),label);
        return n;
    }

    private static androidx.appcompat.app.AlertDialog openDialog(MainActivity a)throws Exception {
        java.util.Set<?> dialogs=(java.util.Set<?>)field("dialogs").get(a);assertEquals(1,dialogs.size());
        return (androidx.appcompat.app.AlertDialog)dialogs.iterator().next();
    }
    private static EditText input(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText e=input(((ViewGroup)view).getChildAt(i));if(e!=null)return e;}
        return null;
    }
    @Test public void guardianPrioritiesAndLiveStatusMessagesRemainTruthfulInEveryLanguage()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);java.util.List<View> cards=new java.util.ArrayList<>();java.util.List<CharSequence> expected=new java.util.ArrayList<>();
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.setBool(Cfg.GUARDIAN_ON,false);cfg.setBool(Cfg.ENABLED,false);cfg.setBool(Cfg.AUTO_COLLECT_SAFE,false);
                java.util.function.Consumer<CharSequence> add=label->{try{cards.add((View)call(a,"heroCard"));expected.add(label);}catch(Exception e){throw new AssertionError(e);}};
                field("paired").set(a,false);add.accept(Language.msg(l10n_future_not_connected));
                field("paired").set(a,true);add.accept(Language.msg(l10n_future_needs_destination));
                cfg.set(Cfg.SAFE_ADDRESS,fixture().rows.get(0).address);cfg.setBool(Cfg.ENABLED,true);assertTrue(cfg.rescueReady());
                add.accept(Language.msg(l10n_future_guardian_off));
                cfg.setBool(Cfg.GUARDIAN_ON,true);field("lastStatus").set(a,null);add.accept(Language.msg(l10n_future_checking));
                Guardian.Status risk=new Guardian.Status();risk.atRiskKnown=2;field("lastStatus").set(a,risk);add.accept(Language.msg(l10n_future_watching_risk,2));
                Guardian.Status waiting=new Guardian.Status();waiting.readySafeN=2;field("lastStatus").set(a,waiting);add.accept(Language.msg(l10n_future_auto_waiting,2));
                cfg.setBool(Cfg.AUTO_COLLECT_SAFE,true);add.accept(Language.msg(l10n_future_protected));
                cfg.set(Cfg.SAFE_REUSED,new org.json.JSONObject().put("addr",fixture().rows.get(0).address).put("count",4).toString());add.accept(Language.msg(l10n_future_safe_reused));
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{Language.select(a,code);for(int i=0;i<cards.size();i++)assertNotNull(text(cards.get(i)),exact(cards.get(i),expected.get(i)));});
        }
    }
    @Test public void safeRiskAndDaemonCardsKeepRawDestinationsAndDynamicAmounts()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final View[] cards=new View[3];String destination=fixture().rows.get(0).address;
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.set(Cfg.SAFE_ADDRESS,destination);cfg.set(Cfg.SAFE_MODE,"samenode");cfg.setBool(Cfg.ENABLED,true);cfg.setBool(Cfg.GUARDIAN_ON,true);cfg.setBool(Cfg.AUTO_COLLECT_SAFE,false);
                Guardian.Status state=new Guardian.Status();state.readySafeN=2;state.readySafeA=12.5;state.readyRiskN=1;state.readyRiskA=5.25;state.vaultLocked=true;
                field("lastStatus").set(a,state);cards[0]=(View)call(a,"daemonCard");cards[1]=(View)call(a,"safeCard");cards[2]=(View)call(a,"riskCard");
                LinearLayout content=(LinearLayout)field("content").get(a);content.removeAllViews();for(View card:cards)content.addView(card);
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{try {
                Language.select(a,code);
                assertTrue(text(cards[0]).contains(destination));assertTrue(text(cards[2]).contains(destination));
                assertNotNull(exact(cards[0],Language.msg(l10n_future_stop_guardian)));
                assertNotNull(exact(cards[1],Language.msg(l10n_future_safe_matured,"12.5",2)));
                assertNotNull(exact(cards[1],Language.msg(l10n_future_turn_on_auto)));
                assertNotNull(exact(cards[1],Language.msg(l10n_future_collect_all)));
                assertNotNull(exact(cards[2],Language.msg(l10n_future_risk_matured,"5.25",1)));
                assertNotNull(exact(cards[2],Language.msg(l10n_future_vault_locked)));
                assertNotNull(exact(cards[2],Language.msg(l10n_future_stop_rescue)));
                assertEquals(destination,((Cfg)field("cfg").get(a)).get(Cfg.SAFE_ADDRESS,""));
            }catch(Exception e){throw new AssertionError(e);}});
            screenshot("future-guardian-uk");
        }
    }
    @Test public void rescueDialogRetainsDraftInBothThemesAndCancelNeverStartsGuardian()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);
            for(Design.Mode mode:Design.Mode.values()) {
                final androidx.appcompat.app.AlertDialog[] dialog=new androidx.appcompat.app.AlertDialog[1];final EditText[] ext=new EditText[1];
                scenario.onActivity(a->{try {
                    Cfg cfg=(Cfg)field("cfg").get(a);assertFalse(cfg.rescueReady());assertFalse(cfg.is(Cfg.GUARDIAN_ON,false));
                    if (Design.mode()!=mode) a.findViewById(R.id.pm_futurecash_next_themeToggle).performClick();
                    View daemon=(View)call(a,"daemonCard");View start=exact(daemon,Language.msg(l10n_future_start_guardian));assertNotNull(start);start.performClick();
                    assertTrue(field("startGuardianAfterSetup").getBoolean(a));dialog[0]=openDialog(a);assertTrue(dialog[0].isShowing());
                    assertEquals(Design.SURFACE(),com.google.android.material.color.MaterialColors.getColor(dialog[0].getContext(),com.google.android.material.R.attr.colorSurfaceContainerHigh,"Future Cash dialog surface"));
                    ext[0]=input(dialog[0].getWindow().getDecorView());assertNotNull(ext[0]);ext[0].setText("MxPUBLIC-Україна-中文");ext[0].setSelection(2,6);
                }catch(Exception e){throw new AssertionError(e);}});
                for(String code:CODES)scenario.onActivity(a->{try {
                    Language.select(a,code);assertSame(dialog[0],openDialog(a));assertTrue(dialog[0].isShowing());
                    View root=dialog[0].getWindow().getDecorView();assertNotNull(exact(root,Language.msg(l10n_future_rescue_title)));
                    assertNotNull(exact(root,Language.msg(l10n_future_fresh_local)));assertNotNull(exact(root,Language.msg(l10n_future_use_external)));
                    assertEquals(Language.msg(l10n_future_cancel).toString(),dialog[0].getButton(-2).getText().toString());
                    assertEquals(Language.msg(l10n_future_external_hint).toString(),ext[0].getHint().toString());
                    assertEquals("MxPUBLIC-Україна-中文",ext[0].getText().toString());assertEquals(2,ext[0].getSelectionStart());assertEquals(6,ext[0].getSelectionEnd());
                    assertFalse(((Cfg)field("cfg").get(a)).is(Cfg.GUARDIAN_ON,false));
                }catch(Exception e){throw new AssertionError(e);}});
                android.os.SystemClock.sleep(3800); // Let the earlier English toast expire before the translated screenshot.
                screenshot("future-rescue-"+mode.name().toLowerCase(java.util.Locale.ROOT)+"-uk");
                scenario.onActivity(a->dialog[0].getButton(-2).performClick());InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->{try {
                    assertFalse(field("startGuardianAfterSetup").getBoolean(a));assertTrue(((java.util.Set<?>)field("dialogs").get(a)).isEmpty());
                    assertFalse(((Cfg)field("cfg").get(a)).is(Cfg.GUARDIAN_ON,false));
                    field("startGuardianAfterSetup").setBoolean(a,true);call(a,"rescueDialog");openDialog(a).cancel();
                }catch(Exception e){throw new AssertionError(e);}});
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->{try {
                    assertFalse(field("startGuardianAfterSetup").getBoolean(a));assertTrue(((java.util.Set<?>)field("dialogs").get(a)).isEmpty());
                }catch(Exception e){throw new AssertionError(e);}});
            }
        }
    }
    @Test public void helpAndResetDialogSwitchLiveWithoutChangingOrClearingStoredLog()throws Exception {
        final androidx.appcompat.app.AlertDialog[] dialog=new androidx.appcompat.app.AlertDialog[1];final String raw="Public diagnostic 0xAbCd | coin:0x123 address:MxPUBLIC Україна 中文";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);
            scenario.onActivity(a->{try{field("tab").set(a,"help");call(a,"buildTabs");call(a,"render");}catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{try {
                Language.select(a,code);View content=(View)field("content").get(a);
                for(int resource:new int[]{l10n_future_help_quick,l10n_future_help_quick_body,l10n_future_help_race_body,l10n_future_help_outage_body,l10n_future_help_matured_body,l10n_future_help_harden_body})assertNotNull(exact(content,Language.msg(resource)));
            }catch(Exception e){throw new AssertionError(e);}});
            screenshot("future-help-uk");
            final String[] stored=new String[1];
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.log(Cfg.LVL_INFO,raw);stored[0]=cfg.logLines().toString();
                field("tab").set(a,"activity");call(a,"buildTabs");call(a,"render");View content=(View)field("content").get(a);
                exact(content,Language.msg(l10n_future_reset_history)).performClick();dialog[0]=openDialog(a);
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{try {
                Language.select(a,code);assertSame(dialog[0],openDialog(a));View root=dialog[0].getWindow().getDecorView();
                assertNotNull(exact(root,Language.msg(l10n_future_reset_title)));assertNotNull(exact(root,Language.msg(l10n_future_reset_body)));
                assertEquals(Language.msg(l10n_future_reset).toString(),dialog[0].getButton(-1).getText().toString());
                assertEquals(stored[0],((Cfg)field("cfg").get(a)).logLines().toString());
                assertTrue(text((View)field("content").get(a)).contains("coin:0x123 address:MxPUBLIC Україна 中文"));
            }catch(Exception e){throw new AssertionError(e);}});
            scenario.onActivity(a->dialog[0].getButton(-2).performClick());InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{try {
                assertEquals(stored[0],((Cfg)field("cfg").get(a)).logLines().toString());
                exact((View)field("content").get(a),Language.msg(l10n_future_reset_history)).performClick();dialog[0]=openDialog(a);
            }catch(Exception e){throw new AssertionError(e);}});
        }
        assertNotNull(dialog[0]);assertFalse("Activity destruction must close the owned dialog",dialog[0].isShowing());
    }

    @Test public void selectedTabStaysVisibleWhenLabelsGrowWithoutReplacingThePaymentDraft()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final EditText[] draft=new EditText[1];
            scenario.onActivity(a->{try {
                field("tab").set(a,"lock");call(a,"buildTabs");call(a,"render");draft[0]=(EditText)field("lockAmount").get(a);draft[0].setText("12.34000");draft[0].setSelection(2,4);
            }catch(Exception e){throw new AssertionError(e);}});
            for(String tab:new String[]{"help","activity","lock","guardian"})for(String code:CODES) {
                scenario.onActivity(a->{try{field("tab").set(a,tab);call(a,"buildTabs");call(a,"render");Language.select(a,code);}catch(Exception e){throw new AssertionError(e);}});
                // Idle can precede the next layout frame and the posted scroll request.
                final boolean[] visibleAfterLayout={false};
                long deadline=android.os.SystemClock.uptimeMillis()+2000;
                do {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(a->{try {
                        int label="help".equals(tab)?l10n_future_help:"activity".equals(tab)?l10n_future_activity:"lock".equals(tab)?l10n_future_stakes:l10n_future_guardian;
                        View selected=exact((View)field("tabsBar").get(a),Language.msg(label));
                        android.graphics.Rect rect=new android.graphics.Rect();
                        visibleAfterLayout[0]=selected!=null&&!selected.isLayoutRequested()&&selected.getWidth()>0&&selected.getGlobalVisibleRect(rect)&&rect.width()==selected.getWidth();
                    }catch(Exception e){throw new AssertionError(e);}});
                    if(!visibleAfterLayout[0])android.os.SystemClock.sleep(30);
                }while(!visibleAfterLayout[0]&&android.os.SystemClock.uptimeMillis()<deadline);
                scenario.onActivity(a->{try {
                    int label="help".equals(tab)?l10n_future_help:"activity".equals(tab)?l10n_future_activity:"lock".equals(tab)?l10n_future_stakes:l10n_future_guardian;
                    View selected=exact((View)field("tabsBar").get(a),Language.msg(label));assertNotNull(selected);
                    android.graphics.Rect visible=new android.graphics.Rect();assertTrue(selected.getGlobalVisibleRect(visible));
                    assertEquals("Selected tab must fit the visible strip: "+tab+"/"+code,selected.getWidth(),visible.width());
                    assertSame(draft[0],field("lockAmount").get(a));assertEquals("12.34000",draft[0].getText().toString());assertEquals(2,draft[0].getSelectionStart());assertEquals(4,draft[0].getSelectionEnd());
                }catch(Exception e){throw new AssertionError(e);}});
            }
            scenario.onActivity(a->{try{field("tab").set(a,"help");call(a,"buildTabs");call(a,"render");}catch(Exception e){throw new AssertionError(e);}});
            screenshot("future-help-tab-uk");
        }
    }

    private static void assertDraft(MainActivity a,EditText[] inputs)throws Exception {
        assertSame(inputs[0],field("lockAmount").get(a));assertSame(inputs[1],field("lockRecipient").get(a));
        assertEquals("12.345600",inputs[0].getText().toString());assertEquals(2,inputs[0].getSelectionStart());assertEquals(5,inputs[0].getSelectionEnd());
        assertEquals("MxPUBLIC-draft-Україна-中文",inputs[1].getText().toString());assertEquals(3,inputs[1].getSelectionStart());
        assertEquals(2,field("lockPreset").getInt(a));assertEquals(12096L,field("lockBlocks").getLong(a));
    }
    @Test public void rawErrorsRemainLiteralWhileAuditFallbackSwitchesLanguage()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final View[] view=new View[1];final String raw="Audit diagnostic 0xAbCd · BLOCK:2350000 · Україна/中文";
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.set(Cfg.AUDIT_LAST_ERR,raw);field("lastVerdict").set(a,null);view[0]=(View)call(a,"auditCard");
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES)scenario.onActivity(a->{try {
                Language.select(a,code);assertTrue(text(view[0]).contains(raw));assertTrue(text(view[0]).contains(Language.msg(l10n_future_audit_paused).toString()));
                assertEquals(raw,((Cfg)field("cfg").get(a)).get(Cfg.AUDIT_LAST_ERR,""));
            }catch(Exception e){throw new AssertionError(e);}});
        }
    }

    @Test public void knownAuditFailuresSwitchLiveWithoutRewritingStoredDiagnostics()throws Exception {
        String[] raw={"Couldn't read keys from the embedded node. Check Minima Core’s status.",
                "Could not read minimaWallet public audit keys. Unlock minimaWallet and retry.",
                "The node returned an unreadable key row. Audit remains incomplete.",
                "minimaWallet changed during the audit. Run the audit again.",
                "Couldn't reach the audit service (Could not reach the audit service. Check your connection and try again.).",
                "Couldn't reach the audit service (public diagnostic 0xAbCd Україна 中文)."};
        int[] resources={l10n_future_error_node_keys,l10n_future_error_wallet_keys,l10n_future_error_unreadable_key,l10n_future_error_wallet_changed};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final View[] card=new View[1];
            for(int i=0;i<raw.length;i++) {
                final int index=i;
                scenario.onActivity(a->{try {
                    Cfg cfg=(Cfg)field("cfg").get(a);cfg.set(Cfg.AUDIT_LAST_ERR,raw[index]);field("lastVerdict").set(a,null);
                    card[0]=(View)call(a,"auditCard");
                    LinearLayout content=(LinearLayout)field("content").get(a);content.removeAllViews();content.addView(card[0]);
                }catch(Exception e){throw new AssertionError(e);}});
                for(String code:CODES)scenario.onActivity(a->{try {
                    Language.select(a,code);
                    CharSequence expected=index<4?Language.msg(resources[index]):Language.msg(l10n_future_error_audit_service,
                            index==4?Language.msg(l10n_future_error_audit_connection):"public diagnostic 0xAbCd Україна 中文");
                    assertTrue(text(card[0]),text(card[0]).contains(expected.toString()));
                    assertTrue(text(card[0]).contains(Language.msg(l10n_future_audit_paused).toString()));
                    assertEquals(raw[index],((Cfg)field("cfg").get(a)).get(Cfg.AUDIT_LAST_ERR,""));
                    assertSame(card[0],((ViewGroup)field("content").get(a)).getChildAt(0));
                }catch(Exception e){throw new AssertionError(e);}});
            }
            screenshot("future-audit-error-uk");
        }
    }

    @Test public void activityLogTranslatesDescriptionsAndFieldsWithoutChangingStoredRecords()throws Exception {
        String source="0xAbCd1234",destination="Mx"+"Ab9".repeat(12),coin="0xEfAb5678";
        String[] raw={"Locked 00012.34000 · matures block 0002350001 | to:"+destination,
                "Swept 2 coin(s) off a reused address (1 failed) | from:"+source+" | to:"+destination,
                "Sweep post failed | coin:"+coin+" | from:"+source+" — failed (at txnpost)",
                "Unknown diagnostic $1 {0} Україна 中文 | address:Mxshort"};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            settle(scenario);final String[] stored=new String[1];final View[] content=new View[1];
            scenario.onActivity(a->{try {
                Cfg cfg=(Cfg)field("cfg").get(a);cfg.clearLog();
                for(int i=0;i<raw.length;i++)cfg.log(i==2?Cfg.LVL_ERROR:Cfg.LVL_INFO,raw[i]);
                stored[0]=cfg.logLines().toString();field("tab").set(a,"activity");call(a,"buildTabs");call(a,"render");content[0]=(View)field("content").get(a);
            }catch(Exception e){throw new AssertionError(e);}});
            for(String code:CODES) {
                scenario.onActivity(a->{try {
                    Language.select(a,code);String display=text(content[0]);
                    assertNotNull(exact(content[0],Language.msg(l10n_future_log_locked,"00012.34000","0002350001")));
                    assertNotNull(exact(content[0],Language.msg(l10n_future_log_swept_coins_partial,"2","1")));
                    assertNotNull(exact(content[0],Language.msg(l10n_future_log_route,source,destination)));
                    assertNotNull(exact(content[0],Language.msg(l10n_future_log_coin_from_error,coin,source,Language.msg(l10n_future_error_stage_failed_txnpost))));
                    for(String literal:new String[]{source,destination,coin,"00012.34000","0002350001","Unknown diagnostic $1 {0} Україна 中文","address:Mxshort"})assertTrue(literal,display.contains(literal));
                    assertEquals(stored[0],((Cfg)field("cfg").get(a)).logLines().toString());
                    assertSame(content[0],field("content").get(a));
                    TextView error=(TextView)exact(content[0],Language.msg(l10n_future_log_sweep_failed));assertNotNull(error);assertEquals(Design.RED(),error.getCurrentTextColor());
                }catch(Exception e){throw new AssertionError(e);}});
            }
            screenshot("future-activity-uk");
        }
    }
}
