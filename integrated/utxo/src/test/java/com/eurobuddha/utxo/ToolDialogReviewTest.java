package com.eurobuddha.utxo;
import androidx.appcompat.app.AlertDialog;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.utxo.TransactionEvidenceTest.*;
import static com.eurobuddha.utxo.UncertainWriteTest.object;

/** Real native UTXO dialog wiring; reuses the Send form fixture. No real node runs. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ToolDialogReviewTest {
    static class Flow implements AutoCloseable {
        final SendDraftReviewTest f=new SendDraftReviewTest();final List<String> commands=new ArrayList<>();final List<NodeApi.Cb> replies=new ArrayList<>();
        final DistributeManager manager=mock(DistributeManager.class);final WalletTools tools;
        Flow()throws Exception {
            f.setup();f.selected=ToolReviewTest.selection();doReturn(manager).when(f.act).distribute();when(manager.reserve()).thenReturn(true);
            doAnswer(i->{commands.add(i.getArgument(0));replies.add(i.getArgument(1));return null;}).when(f.node).cmd(anyString(),any());
            tools=new WalletTools(f.act,mock(WalletTools.Status.class));
        }
        void open(String op){if(op.equals("split"))tools.showSplit();else if(op.equals("merge"))tools.showConsolidate();else tools.showDistribute();}
        void accept(){((AlertDialog)ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();}
        void answer(){while(!replies.isEmpty())replies.remove(0).onResult(object("status",true,"response",object("address",CHANGE)));}
        public void close(){f.close();}
    }
    @Test public void nativeSplitAndMergeDialogsRejectChangedSelectionsBeforeLookup()throws Exception {
        for(String op:new String[]{"split","merge"})try(Flow f=new Flow()){
            f.open(op);f.f.selected.get(0).rawAmount="99";f.accept();assertTrue(f.commands.isEmpty());assertTrue(f.f.built.isEmpty());verify(f.f.history,never()).insertPosting(any());
        }
    }
    @Test public void nativeSplitCannotSubmitAfterSelectionChangesDuringAddressLookup()throws Exception {
        try(Flow f=new Flow()){
            f.open("split");f.accept();assertEquals(1,f.commands.size());f.f.selected=Collections.singletonList(coin("0x00",'9',"10"));f.answer();
            assertTrue(f.f.built.isEmpty());verify(f.f.history,never()).insertPosting(any());
        }
    }
    @Test public void nativeUntrackDialogUsesFrozenReviewedCoinIds()throws Exception {
        try(Flow f=new Flow()){
            f.tools.untrackSelected();f.accept();assertEquals(1,f.commands.size());f.f.selected.get(1).coinid="0x00;send";f.answer();
            assertEquals(Arrays.asList("cointrack enable:false coinid:"+id('1'),"cointrack enable:false coinid:"+id('2')),f.commands);verify(f.f.act,never()).clearSelection();
        }
    }
}
