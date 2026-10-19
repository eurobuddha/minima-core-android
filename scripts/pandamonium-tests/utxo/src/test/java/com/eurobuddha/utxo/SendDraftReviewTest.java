package com.eurobuddha.utxo;

import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Reuses NFT ToolReviewTest and TransactionOutcomeCallbacksTest with the real UTXO form.
 * Only mocked node/builders are used; no signing or network transport runs. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SendDraftReviewTest {
    MainActivity act;SendView send;NodeApi node;HistoryDb history;
    List<Coin> selected;List<NodeApi.Cb> checks=new ArrayList<>();
    List<List<?>> built=new ArrayList<>();MockedConstruction<TxnBuilder> builders;
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        act=spy(Robolectric.buildActivity(MainActivity.class).get());act.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        node=mock(NodeApi.class);history=mock(HistoryDb.class);selected=selection();
        doReturn(node).when(act).node();doReturn(history).when(act).history();when(history.insertPosting(any())).thenReturn(true);
        doAnswer(i->selected).when(act).selectedCoins();doReturn("0x00").when(act).selectedTokenid();doReturn(11).when(act).tokenDecimals(any());
        doReturn(Collections.emptyList()).when(act).myAddresses();doNothing().when(act).clearSelection();doNothing().when(act).reload();
        doAnswer(i->{String c=i.getArgument(0);if(c.startsWith("checkaddress "))checks.add(i.getArgument(1));else assertEquals("getaddress",c);return null;}).when(node).cmd(anyString(),any());
        builders=mockConstruction(TxnBuilder.class,(b,c)->{built.add(c.arguments());when(b.record(any())).thenReturn(b);when(b.onProgress(any())).thenReturn(b);});
        send=new SendView(act);act.setContentView(send.getRoot());send.setFieldValues(new String[]{DEST,"3",CHANGE,""});
    }
    @After public void close(){builders.close();if(ShadowDialog.getLatestDialog()!=null)ShadowDialog.getLatestDialog().dismiss();act.finish();Language.select(RuntimeEnvironment.getApplication(),"en");}
    static List<Coin> selection(){Coin c=coin("0x00",'1',"10");c.tokenName="Minima";return new ArrayList<>(Collections.singletonList(c));}
    Object get(String name)throws Exception{Field f=SendView.class.getDeclaredField(name);f.setAccessible(true);return f.get(send);}
    void preview()throws Exception{Method m=SendView.class.getDeclaredMethod("onPreview");m.setAccessible(true);m.invoke(send);}
    Button button()throws Exception{return (Button)get("previewBtn");}
    void confirm()throws Exception{preview();checks.remove(0).onResult(object("status",true,"response",object("valid",true)));}
    void sign(){((AlertDialog)ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
    TxnBuilder.Done callback(){return (TxnBuilder.Done)built.get(0).get(5);}
    @Test public void delayedAddressCheckCannotOpenAReviewForEditedFields()throws Exception {
        for(String language:new String[]{"en","zh-Hans","ru","uk"}){
            Language.select(act,language);send.setFieldValues(new String[]{DEST,"3",CHANGE,""});preview();assertFalse(button().isEnabled());
            send.setFieldValues(new String[]{id('d'),"4",CHANGE,""});checks.remove(0).onResult(object("status",true));
            assertTrue(button().isEnabled());assertTrue(built.isEmpty());assertNull(ShadowDialog.getLatestDialog());
            assertEquals(Language.text(act,com.eurobuddha.minimacore.i18n.R.string.l10n_nft_send_review_again),((TextView)get("statusView")).getText().toString());
        }
        verify(history,never()).insertPosting(any());
    }
    @Test public void staleConfirmationCannotSpendChangedCoinEconomics()throws Exception {
        confirm();selected.get(0).rawAmount="11";sign();assertTrue(built.isEmpty());verify(history,never()).insertPosting(any());
    }
    @Test public void unchangedRefreshUsesFrozenReviewedInputs()throws Exception {
        List<Coin> old=selected;confirm();selected=selection();old.get(0).amount="999";old.get(0).coinid=id('9');sign();
        assertEquals(1,built.size());List<Coin> frozen=(List<Coin>)built.get(0).get(1);assertEquals("10",frozen.get(0).amount);assertEquals(id('1'),frozen.get(0).coinid);assertNotSame(selected.get(0),frozen.get(0));
        assertEquals("0x00",built.get(0).get(3));List<TxnBuilder.Out> outs=(List<TxnBuilder.Out>)built.get(0).get(2);assertEquals(DEST,outs.get(0).address);assertEquals("3",outs.get(0).amount);assertEquals("7",outs.get(1).amount);
    }
    @Test public void refreshDuringSubmissionCannotEnableAnotherPreviewAndSuccessPreservesANewerDraft()throws Exception {
        confirm();sign();assertEquals(1,built.size());send.refresh();assertFalse(button().isEnabled());preview();assertTrue(checks.isEmpty());
        String[] later={id('d'),"2",CHANGE,""};send.setFieldValues(later);callback().onPosted(id('f'),Collections.emptyList());
        assertArrayEquals(later,send.fieldValues());verify(act,never()).clearSelection();verify(act).reload();assertTrue(button().isEnabled());assertFalse((Boolean)get("sending"));
    }
    @Test public void lateSuccessCannotEraseAReplacementDraftOrSelection()throws Exception {
        confirm();sign();String[] later={id('d'),"2",CHANGE,""};send.setFieldValues(later);
        selected=Collections.singletonList(coin("0x00",'9',"5"));callback().onPosted(id('f'),Collections.emptyList());
        assertArrayEquals(later,send.fieldValues());verify(act,never()).clearSelection();verify(act).reload();
    }
    @Test public void aRepeatedConfirmationCannotResubmitAfterFailure()throws Exception {
        confirm();AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();sign();callback().onFailed("Public rejection");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,built.size());
    }
    @Test public void matchingSuccessClearsOnlyTheSubmittedDraft()throws Exception {
        confirm();sign();callback().onPosted(id('f'),Collections.emptyList());assertArrayEquals(new String[]{"","","",""},send.fieldValues());verify(act).clearSelection();
    }
    @Test public void unknownKeepsDraftAndSelectionAndDoesNotReportFailure()throws Exception {
        confirm();sign();String[] draft=send.fieldValues();callback().onUnknown(TransactionEvidence.UNKNOWN);
        assertArrayEquals(draft,send.fieldValues());verify(act,never()).clearSelection();verify(history,never()).update(any(),eq(HistoryDb.STATUS_ERROR),any(),any());assertTrue(button().isEnabled());assertFalse((Boolean)get("sending"));
    }
    @Test public void earlierRepliesCannotReenableANewerReview()throws Exception {
        preview();NodeApi.Cb first=checks.remove(0);preview();first.onError("old failure");assertFalse(button().isEnabled());
        send.setFieldValues(new String[]{DEST,"2",CHANGE,""});checks.remove(0).onResult(object("status",true));assertTrue(button().isEnabled());assertTrue(built.isEmpty());
    }
    @Test public void closingActivityCannotAcceptAVisibleConfirmation()throws Exception {
        confirm();doReturn(true).when(act).isFinishing();sign();assertTrue(built.isEmpty());verify(history,never()).insertPosting(any());
    }
}
