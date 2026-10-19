package com.eurobuddha.atomix;
import com.eurobuddha.atomix.eth.JournalFixture;

import android.app.Dialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.eurobuddha.atomix.eth.*;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.web3j.crypto.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses ETH Wallet AssetSelectionTest's actual-dialog/queued-work fixture. No live RPC or funds. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class ManualSendContextTest {
    static final Credentials FIRST=Credentials.create("01"), SECOND=Credentials.create("02");
    static final String TO="0x"+"12".repeat(20), HASH="0x"+"34".repeat(32);
    static final BigInteger GP=BigInteger.valueOf(1_000_000_000L), REVIEWED=BigInteger.valueOf(1_125_000_000L);
    MainActivity activity; EthRpc rpc; EthWallet wallet; ExecutorService worker;
    final List<Runnable> jobs=new ArrayList<>();
    Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    void set(String name,Object value)throws Exception {field(name).set(activity,value);}
    void owner(Credentials value)throws Exception {Field f=EthWallet.class.getDeclaredField("creds");f.setAccessible(true);f.set(wallet,value);}
    void open()throws Exception {Method m=MainActivity.class.getDeclaredMethod("sendDialog");m.setAccessible(true);m.invoke(activity);idle();}
    void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    Dialog dialog(){idle();return ShadowDialog.getLatestDialog();}
    Button positive(Dialog d){return d.findViewById(android.R.id.button1);}
    void job(){assertFalse(jobs.isEmpty());jobs.remove(0).run();idle();}
    static List<EditText> inputs(View view){List<EditText> out=new ArrayList<>();if(view instanceof EditText)out.add((EditText)view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)out.addAll(inputs(((ViewGroup)view).getChildAt(i)));return out;}
    static TextView text(View view,String value){if(view instanceof TextView&&value.equalsIgnoreCase(((TextView)view).getText().toString()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    static String contentText(View view){StringBuilder out=new StringBuilder();if(view instanceof TextView)out.append(((TextView)view).getText());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)out.append(contentText(((ViewGroup)view).getChildAt(i)));return out.toString();}
    void form(Dialog d,boolean eth,String amount){View decor=d.getWindow().getDecorView();text(decor,eth?"ETH":"USDT").performClick();List<EditText> in=inputs(decor);in.get(0).setText(TO);in.get(1).setText(amount);}
    Dialog review(boolean eth,String amount)throws Exception {open();Dialog d=dialog();form(d,eth,amount);positive(d).performClick();job();Dialog result=dialog();assertNotSame(d,result);return result;}
    @Before public void setup()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);Language.select(RuntimeEnvironment.getApplication(),"en");
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.pm_atomix_Theme_Utxo);
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        doAnswer(inv->{jobs.add(inv.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        wallet=(EthWallet)field("wallet").get(activity);owner(FIRST);rpc=mock(EthRpc.class);set("rpc",rpc);set("modalOpen",true);
        set("prefs",activity.getSharedPreferences("manual-send-fixture",0));
        when(rpc.callStr(eq("eth_gasPrice"),any(JSONArray.class))).thenReturn("0x3b9aca00");
        when(rpc.baseFeePerGasOrZero()).thenReturn(BigInteger.ZERO);
        when(rpc.getBalance(anyString())).thenReturn(BigInteger.TEN.pow(18));
        when(rpc.ethCall(anyString(),anyString())).thenReturn("0x"+String.format("%064x",BigInteger.valueOf(50_000_000)));
        when(rpc.getTransactionCount(anyString())).thenReturn(BigInteger.valueOf(4));
        JournalFixture.install();JournalFixture.wire(rpc);EthTx.resetAll();
    }
    @After public void cleanup()throws Exception {Dialog d=ShadowDialog.getLatestDialog();if(d!=null)d.dismiss();idle();((ExecutorService)field("io").get(activity)).shutdownNow();jobs.clear();}
    @Test public void editedOrSwitchedMaxCannotOverwriteTheForm()throws Exception {
        open();Dialog d=dialog();View decor=d.getWindow().getDecorView();EditText amount=inputs(decor).get(1);
        amount.setText("1");text(decor,"Max").performClick();amount.setText("7");job();assertEquals("7",amount.getText().toString());
        text(decor,"Max").performClick();text(decor,"USDT").performClick();text(decor,"ETH").performClick();job();assertEquals("7",amount.getText().toString());
    }
    @Test public void changedOwnerDuringBalanceReadCannotOpenConfirmation()throws Exception {
        open();Dialog original=dialog();form(original,true,"0.1");
        when(rpc.getBalance(anyString())).thenAnswer(inv->{owner(SECOND);return BigInteger.TEN.pow(18);});
        positive(original).performClick();job();assertSame(original,dialog());assertTrue(original.isShowing());
        verify(rpc,never()).getTransactionCount(anyString());verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void editedFormDuringPreflightDoesNotDisappearIntoOldReview()throws Exception {
        open();Dialog original=dialog();form(original,true,"0.1");positive(original).performClick();
        inputs(original.getWindow().getDecorView()).get(1).setText("0.2");job();
        assertSame(original,dialog());assertTrue(positive(original).isEnabled());
    }
    @Test public void cancelledPreflightCannotOpenReviewOrReachRpc()throws Exception {
        open();Dialog original=dialog();form(original,true,"0.1");positive(original).performClick();original.dismiss();idle();job();
        assertSame(original,dialog());assertFalse(original.isShowing());verifyNoInteractions(rpc);
    }
    @Test public void changedConnectionCannotUseAnOldConfirmation()throws Exception {
        Dialog confirm=review(true,"0.1");set("rpc",mock(EthRpc.class));positive(confirm).performClick();idle();
        assertTrue(jobs.isEmpty());verify(rpc,never()).getTransactionCount(anyString());
    }
    @Test public void queuedSendChecksOwnerBeforeReadingNonce()throws Exception {
        Dialog confirm=review(true,"0.1");positive(confirm).performClick();idle();owner(SECOND);job();
        verify(rpc,never()).getTransactionCount(anyString());verify(rpc,never()).sendRawTransaction(anyString());
    }
    @Test public void reviewedEthFeeAndOwnerAreExactlyWhatIsSigned()throws Exception {
        Dialog confirm=review(true,"0.1");String content=contentText(confirm.getWindow().getDecorView());assertTrue(content.contains(FIRST.getAddress()));assertTrue(content.contains(TO));assertTrue(content.contains("0.00002362"));
        when(rpc.callStr(eq("eth_gasPrice"),any())).thenReturn("0x77359400");
        when(rpc.sendRawTransaction(anyString())).thenAnswer(inv->{SignedRawTransaction raw=(SignedRawTransaction)TransactionDecoder.decode(inv.getArgument(0));
            assertEquals(FIRST.getAddress(),raw.getFrom());assertEquals(TO,raw.getTo());assertEquals(BigInteger.TEN.pow(17),raw.getValue());assertEquals(REVIEWED,raw.getGasPrice());return JournalFixture.accept(inv.getArgument(0));});
        positive(confirm).performClick();idle();job();verify(rpc,times(1)).callStr(eq("eth_gasPrice"),any());verify(rpc,times(1)).sendRawTransaction(anyString());
    }
    @Test public void reviewedTokenAmountAndContractRemainExact()throws Exception {
        Dialog confirm=review(false,"1.1234567");
        when(rpc.sendRawTransaction(anyString())).thenAnswer(inv->{RawTransaction raw=TransactionDecoder.decode(inv.getArgument(0));
            assertEquals(EthNet.MAINNET.tokens[0].address,raw.getTo());assertEquals(BigInteger.ZERO,raw.getValue());
            assertEquals(EthSend.buildTransferData(TO,BigInteger.valueOf(1_123_456)).substring(2),raw.getData());assertEquals(REVIEWED,raw.getGasPrice());return JournalFixture.accept(inv.getArgument(0));});
        positive(confirm).performClick();idle();job();verify(rpc).sendRawTransaction(anyString());
    }
    @Test public void uncertainSubmissionMessageSurvivesContextCleanup()throws Exception {
        Dialog confirm=review(true,"0.1");when(rpc.sendRawTransaction(anyString())).thenThrow(new java.io.IOException("fixture RPC unavailable"));
        positive(confirm).performClick();idle();job();
        String shown=org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
        assertTrue(shown.contains(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_nft_transaction_unknown)));
        assertTrue(shown.contains("0x"));
        assertFalse(shown.startsWith("Send failed"));
    }
    @Test public void confirmationAndFeeErrorsFollowAllFourLanguages()throws Exception {
        Dialog confirm=review(true,"0.1");
        for(String locale:new String[]{"en","zh-Hans","ru","uk"}) {
            Language.select(activity,locale);idle();String content=contentText(confirm.getWindow().getDecorView());
            assertTrue(content.contains(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_eth_wallet_your_address)));
            assertTrue(content.contains(FIRST.getAddress()));assertTrue(content.contains(TO));assertTrue(content.contains("0.00002362"));
        }
        confirm.dismiss();idle();
        when(rpc.callStr(eq("eth_gasPrice"),any())).thenReturn("0x"+EthTx.MAX_GAS_PRICE.multiply(BigInteger.TEN).toString(16));
        for(String locale:new String[]{"en","zh-Hans","ru","uk"}) {
            Language.select(activity,locale);open();Dialog form=dialog();form(form,true,"0.1");positive(form).performClick();job();
            assertEquals(Language.text(activity,com.eurobuddha.minimacore.i18n.R.string.l10n_atomix_send_fee_cap),org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            form.dismiss();idle();
        }
        verify(rpc,never()).getTransactionCount(anyString());
    }
    @Test public void rejectedExecutorDoesNotCrashOrDispatch()throws Exception {
        Dialog confirm=review(true,"0.1");doThrow(new RejectedExecutionException()).when(worker).execute(any());
        positive(confirm).performClick();idle();assertTrue(jobs.isEmpty());verify(rpc,never()).getTransactionCount(anyString());
    }
}
