package com.eurobuddha.nftwallet;

import android.widget.*;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimaapi.MinimaAPIListener;
import org.json.*;
import org.junit.Test;
import org.mockito.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.eurobuddha.nftwallet.TransactionEvidenceTest.*;
import static com.eurobuddha.nftwallet.BurialEvidenceTest.object;

/** Reuse real NodeApi/mock transport and the existing builder-callback fixture. No node runs. */
public class ToolReviewTest {
    static List<Coin> selection(){List<Coin> out=new ArrayList<>(Arrays.asList(coin("0x00",'1',"4"),coin("0x00",'2',"6")));for(Coin c:out)c.tokenName="Literal 中文";return out;}
    static class Flow extends PreparedSendFlowTest.Flow {
        List<Coin> selected=selection();final WalletTools.Status status=mock(WalletTools.Status.class);
        final DistributeManager manager=mock(DistributeManager.class);final WalletTools tools=new WalletTools(act,status);
        final List<MinimaAPIListener> replies=new ArrayList<>();final List<List<?>> built=new ArrayList<>();
        final MockedConstruction<TxnBuilder> builders=mockConstruction(TxnBuilder.class,(b,c)->{built.add(c.arguments());when(b.record(any())).thenReturn(b);});
        final Class<?> type;
        Flow()throws Exception {
            type=Class.forName("com.eurobuddha.nftwallet.WalletTools$Review");
            when(act.selectedCoins()).thenAnswer(i->selected);when(act.selectedTokenid()).thenReturn(TOKEN);when(act.tokenDecimals(any())).thenReturn(11);
            when(act.distribute()).thenReturn(manager);when(manager.reserve()).thenReturn(true);
            doAnswer(i->{String command=i.getArgument(0);commands.add(command);replies.add(i.getArgument(1));return null;}).when(clients.constructed().get(0)).Command(anyString(),any());
        }
        Object review()throws Exception{Constructor<?> c=type.getDeclaredConstructor(WalletTools.class);c.setAccessible(true);return c.newInstance(tools);}
        void invoke(String name,Object review,String... values)throws Exception{
            Class<?>[] types=new Class<?>[values.length+1];Object[] args=new Object[types.length];types[0]=type;args[0]=review;
            for(int i=0;i<values.length;i++){types[i+1]=String.class;args[i+1]=values[i];}
            Method m=WalletTools.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(tools,args);
        }
        void reply(){replies.remove(0).response(object("status",true,"response",object("address",CHANGE)));}
        void addresses(){while(!replies.isEmpty())reply();}
        TxnBuilder.Done result(){return (TxnBuilder.Done)built.get(0).get(4);}
        List<Coin> inputs(){return (List<Coin>)built.get(0).get(1);}
        public void close(){builders.close();super.close();}
    }
    @Test public void editedSelectionCannotReachAddressLookupOrHistory()throws Exception {
        for(int mode=0;mode<8;mode++)try(Flow f=new Flow()){
            Object review=f.review();Coin c=f.selected.get(0);
            if(mode==0)f.selected.clear();if(mode==1)c.tokenid=TOKEN;if(mode==2)c.amount="99";if(mode==3)c.rawAmount="0.1";
            if(mode==4)c.confirmed=false;if(mode==5)c.sendable=false;if(mode==6)c.spent=true;if(mode==7)c.coinid=id('9');
            f.invoke("doSplit",review,"2");assertTrue(f.commands.isEmpty());verify(f.history,never()).insertPosting(any());assertTrue(f.built.isEmpty());verify(f.status).show(any(Language.Message.class),eq(false));
        }
    }
    @Test public void originallyUnspendableOrMalformedSelectionsCannotStartATransfer()throws Exception {
        for(int mode=0;mode<4;mode++)try(Flow f=new Flow()){
            Coin c=f.selected.get(0);if(mode==0)c.sendable=false;if(mode==1)c.confirmed=false;if(mode==2)c.spent=true;if(mode==3)c.rawAmount="1;send";
            f.invoke("mergeSelected",f.review());assertTrue(f.commands.isEmpty());assertTrue(f.built.isEmpty());
        }
    }
    @Test public void delayedSplitAndMergeRepliesCannotSpendANewSelection()throws Exception {
        for(String operation:new String[]{"doSplit","mergeSelected"})try(Flow f=new Flow()){
            Object review=f.review();if(operation.equals("doSplit"))f.invoke(operation,review,"2");else f.invoke(operation,review);
            assertEquals(1,f.replies.size());f.selected=Collections.singletonList(coin(TOKEN,'9',"10"));f.addresses();
            assertTrue(f.built.isEmpty());verify(f.history,never()).insertPosting(any());verify(f.status).show(any(Language.Message.class),eq(false));
        }
    }
    @Test public void anIdenticalRefreshUsesFrozenCoinsAndReviewedTokenWithExactSplitRemainder()throws Exception {
        try(Flow f=new Flow()){
            List<Coin> old=f.selected;Object review=f.review();f.selected=selection();old.get(0).amount="999";old.get(0).coinid=id('9');
            f.invoke("doSplit",review,"3");f.addresses();assertEquals(1,f.built.size());assertEquals("0x00",f.built.get(0).get(3));
            assertEquals(id('1'),f.inputs().get(0).coinid);assertEquals("4",f.inputs().get(0).amount);assertNotSame(f.selected.get(0),f.inputs().get(0));
            List<TxnBuilder.Out> out=(List<TxnBuilder.Out>)f.built.get(0).get(2);assertEquals(3,out.size());assertEquals("3.33333333334",out.get(2).amount);
            BigDecimal sum=BigDecimal.ZERO;for(TxnBuilder.Out x:out)sum=sum.add(new BigDecimal(x.amount));assertEquals(0,new BigDecimal("10").compareTo(sum));
        }
    }
    @Test public void abandonedDistributionLookupReleasesItsStartReservation()throws Exception {
        try(Flow f=new Flow()){
            f.invoke("doDistribute",f.review(),"2","1");verify(f.manager).reserve();f.selected.get(0).rawAmount="99";f.addresses();
            verify(f.manager).cancelReserve();verify(f.manager,never()).start(any(),any(),any(),any(),any());assertTrue(f.built.isEmpty());
        }
    }
    @Test public void distributionReceivesIndependentReviewedCoinsForItsLaterAsyncBuild()throws Exception {
        try(Flow f=new Flow()){
            f.invoke("doDistribute",f.review(),"2","1");f.addresses();ArgumentCaptor<List<Coin>> inputs=ArgumentCaptor.forClass(List.class);
            verify(f.manager).start(inputs.capture(),eq(Arrays.asList(CHANGE,CHANGE)),eq("1"),eq("0x00"),eq("Literal 中文"));
            f.selected.get(0).coinid=id('9');f.selected.get(0).amount="999";assertEquals(id('1'),inputs.getValue().get(0).coinid);assertEquals("4",inputs.getValue().get(0).amount);
            verify(f.manager,never()).cancelReserve();
        }
    }
    @Test public void successfulToolReplyOnlyClearsTheMatchingSubmittedSelection()throws Exception {
        for(boolean changed:new boolean[]{false,true})try(Flow f=new Flow()){
            f.invoke("doSplit",f.review(),"2");f.addresses();if(changed)f.selected=Collections.singletonList(coin(TOKEN,'9',"10"));
            f.result().onPosted(id('f'),Collections.emptyList());verify(f.act,times(changed?0:1)).clearSelection();verify(f.act).reload();
            verify(f.history).update(anyString(),eq(HistoryDb.STATUS_POSTED),isNull(),eq(""));
        }
    }
    @Test public void untrackKeepsItsReviewedWatchOnlyTargetsAndPreservesANewSelection()throws Exception {
        try(Flow f=new Flow()){
            for(Coin c:f.selected){c.confirmed=false;c.sendable=false;}
            Object review=f.review();Method accept=f.type.getDeclaredMethod("accept",boolean.class);accept.setAccessible(true);assertEquals(true,accept.invoke(review,false));
            Field coins=f.type.getDeclaredField("coins");coins.setAccessible(true);Method next=WalletTools.class.getDeclaredMethod("untrackNext",List.class,int.class,WalletTools.Status.class);next.setAccessible(true);Field owner=f.type.getDeclaredField("status");owner.setAccessible(true);next.invoke(f.tools,coins.get(review),0,owner.get(review));
            f.selected.get(1).coinid="0x01;send";f.addresses();assertEquals(Arrays.asList("cointrack enable:false coinid:"+id('1'),"cointrack enable:false coinid:"+id('2')),f.commands);
            verify(f.act,never()).clearSelection();verify(f.act).reload();
        }
    }
    @Test public void closedActivityAndRepeatedConfirmationCannotStartAnotherLookup()throws Exception {
        try(Flow f=new Flow()){Object review=f.review();when(f.act.isDestroyed()).thenReturn(true);f.invoke("doSplit",review,"2");assertTrue(f.commands.isEmpty());}
        try(Flow f=new Flow()){Object review=f.review();f.invoke("doSplit",review,"2");f.invoke("doSplit",review,"2");assertEquals(1,f.replies.size());f.addresses();assertEquals(1,f.built.size());}
    }
    @Test public void sendReviewAlsoRejectsChangedRawValueConfirmationAndSpentState()throws Exception {
        for(int mode=0;mode<3;mode++)try(Flow f=new Flow();MockedStatic<Language> language=mockStatic(Language.class,CALLS_REAL_METHODS)){
            SendView send=mock(SendView.class,CALLS_REAL_METHODS);Button preview=mock(Button.class);TextView label=mock(TextView.class);
            TransactionOutcomeCallbacksTest.set(BaseView.class,send,"act",f.act);TransactionOutcomeCallbacksTest.set(SendView.class,send,"previewBtn",preview);TransactionOutcomeCallbacksTest.set(SendView.class,send,"statusView",label);
            language.when(()->Language.setText(eq(label),any(CharSequence.class))).thenAnswer(i->null);String[] fields={"public"};doReturn(fields).when(send).fieldValues();
            Method snapshot=SendView.class.getDeclaredMethod("selectionSnapshot");snapshot.setAccessible(true);String[] coins=(String[])snapshot.invoke(send);
            if(mode==0)f.selected.get(0).rawAmount="0.01";if(mode==1)f.selected.get(0).confirmed=false;if(mode==2)f.selected.get(0).spent=true;
            Method accept=SendView.class.getDeclaredMethod("acceptReview",long.class,String[].class,String[].class);accept.setAccessible(true);assertEquals(false,accept.invoke(send,0L,fields,coins));assertTrue(f.commands.isEmpty());
        }
    }
}
