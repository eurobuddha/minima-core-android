package com.eurobuddha.pandadex;

import android.content.DialogInterface;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DexMakerDialogMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception{return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception{assertEquals(id,field(value,"resource"));}
    private static List<Object> matching(Object value,int id)throws Exception {
        List<Object> found=new ArrayList<>();if(!(value instanceof Language.Message))return found;
        if(field(value,"resource").equals(id))found.add(value);
        for(Object a:args(value))found.addAll(matching(a,id));return found;
    }
    private static Object one(Object value,int id)throws Exception {List<Object> found=matching(value,id);assertEquals(1,found.size());return found.get(0);}
    private static MakerLadder.Level level(String price,String size){return new MakerLadder.Level(new BigDecimal(price),new BigDecimal(size));}
    private static MakerLadder.Commitments commitments() {
        List<MakerLadder.Slot> desired=MakerLadder.desired(null,new MakerLadder.Config(false,BigDecimal.ZERO,
                Arrays.asList(level("1.25","10"),level("1.50","20")),Arrays.asList(level("0.50","3"),level("0.25","4")),BigDecimal.ZERO,BigDecimal.ONE),BigDecimal.ONE);
        return MakerLadder.commitments(desired);
    }
    @Test public void actualCommitmentsKeepBothTokensAndPeggedManualWarnings()throws Exception {
        MakerLadder.Commitments c=commitments();assertEquals(0,new BigDecimal("30").compareTo(c.askMinima));assertEquals(0,new BigDecimal("2.5").compareTo(c.bidUsdt));
        for(boolean pegged:new boolean[]{true,false}) {
            Object display=MainActivity.makerPublishMessage(2,2,c,pegged,MainActivity.makerFundingHint(2,2,1,1));
            Object summary=one(display,R.string.l10n_dex_maker_publish_summary);assertEquals("4",args(summary)[0]);
            assertArrayEquals(new Object[]{"2","30"},args(one(display,R.string.l10n_dex_maker_ask_commitment)));
            assertArrayEquals(new Object[]{"2","2.5"},args(one(display,R.string.l10n_dex_maker_bid_commitment)));
            one(display,pegged?R.string.l10n_dex_maker_pegged_consent:R.string.l10n_dex_maker_manual_consent);
            assertTrue(matching(display,pegged?R.string.l10n_dex_maker_manual_consent:R.string.l10n_dex_maker_pegged_consent).isEmpty());
            one(display,R.string.l10n_dex_maker_posting_consent);one(display,R.string.l10n_dex_maker_background_consent);
            one(display,R.string.l10n_dex_maker_thin_minima);one(display,R.string.l10n_dex_maker_thin_mxusd);one(display,R.string.l10n_dex_maker_split_hint);
        }
    }
    @Test public void singleSidedConsentDoesNotInventOrdersOrFundingHints()throws Exception {
        MakerLadder.Commitments c=MakerLadder.commitments(MakerLadder.desired(null,new MakerLadder.Config(false,BigDecimal.ZERO,
                Collections.singletonList(level("1.25","10")),Collections.emptyList(),BigDecimal.ZERO,BigDecimal.ONE),BigDecimal.ONE));
        Object display=MainActivity.makerPublishMessage(1,0,c,false,MainActivity.makerFundingHint(1,0,1,0));
        assertArrayEquals(new Object[]{"1","10"},args(one(display,R.string.l10n_dex_maker_ask_commitment)));
        one(display,R.string.l10n_dex_maker_no_bids);assertTrue(matching(display,R.string.l10n_dex_maker_bid_commitment).isEmpty());
        assertTrue(matching(display,R.string.l10n_dex_maker_split_hint).isEmpty());
    }
    @Test public void shortagePreservesAvailableLockedAndUnconfirmedAmounts()throws Exception {
        MakerLadder.Commitments c=commitments();BigDecimal zero=BigDecimal.ZERO;
        for(int mode=0;mode<4;mode++) {
            BigDecimal locked=(mode&1)==0?zero:new BigDecimal("1.25"),pending=(mode&2)==0?zero:new BigDecimal("2.75");
            String raw=MainActivity.makerFundingLack(c,new BigDecimal("20"),locked,pending,BigDecimal.ONE,locked,pending);
            Object display=DexMessages.quote(raw);Object ask=one(display,R.string.l10n_dex_maker_lack_minima),bid=one(display,R.string.l10n_dex_maker_lack_mxusd);
            assertEquals("30",args(ask)[0]);assertEquals("20",args(ask)[1]);assertEquals("2.5",args(bid)[0]);assertEquals("1",args(bid)[1]);
            if(mode==0)assertEquals("",args(ask)[2]);
            else {int id=mode==3?R.string.l10n_dex_maker_unavailable_both:mode==1?R.string.l10n_dex_maker_unavailable_locked:R.string.l10n_dex_maker_unavailable_pending;
                resource(args(ask)[2],id);assertArrayEquals(mode==3?new Object[]{"1.25","2.75"}:new Object[]{mode==1?"1.25":"2.75"},args(args(ask)[2]));}
        }
        assertEquals("",MainActivity.makerFundingLack(c,new BigDecimal("30"),zero,zero,new BigDecimal("2.5"),zero,zero));
    }
    @Test public void editConsentKeepsRepriceRepostCreateAndCancelSeparate()throws Exception {
        Object all=MainActivity.makerEditMessage(1,2,3,4);
        int[] ids={R.string.l10n_dex_maker_edit_relocks,R.string.l10n_dex_maker_edit_reposts,R.string.l10n_dex_maker_edit_creates,R.string.l10n_dex_maker_edit_cancels};
        for(int i=0;i<ids.length;i++)assertArrayEquals(new Object[]{""+(i+1)},args(one(all,ids[i])));
        one(all,R.string.l10n_dex_maker_edit_timing);
        Object only=MainActivity.makerEditMessage(0,1,0,0);one(only,ids[1]);assertTrue(matching(only,ids[0]).isEmpty());assertTrue(matching(only,ids[2]).isEmpty());assertTrue(matching(only,ids[3]).isEmpty());
        Object plural=MainActivity.makerEditMessage(2,2,2,2);for(int id:ids)assertArrayEquals(new Object[]{"2"},args(one(plural,id)));
    }
    @Test public void fundingMenuAndWarningUseSeparateContentPanelsAndRetainSelectedAmount()throws Exception {
        for(int selected:new int[]{0,1}) {
            MainActivity act=mock(MainActivity.class);doCallRealMethod().when(act).showMakerFundingChoices(any(),any(),anyInt(),anyInt());
            try(MockedConstruction<LanguageDialog> dialogs=mockConstruction(LanguageDialog.class,withSettings().defaultAnswer(RETURNS_SELF))) {
                act.showMakerFundingChoices(new BigDecimal("12.34000"),new BigDecimal("56.78000"),1,2);
                assertEquals(1,dialogs.constructed().size());LanguageDialog menu=dialogs.constructed().get(0);
                ArgumentCaptor<CharSequence[]> items=ArgumentCaptor.forClass(CharSequence[].class);
                ArgumentCaptor<DialogInterface.OnClickListener> choose=ArgumentCaptor.forClass(DialogInterface.OnClickListener.class);
                verify(menu).setItems(items.capture(),choose.capture());assertEquals(2,items.getValue().length);
                verify(menu,never()).setMessage(any(CharSequence.class));verify(menu,never()).setPositiveButton(any(CharSequence.class),any());
                resource(items.getValue()[0],R.string.l10n_dex_maker_funding_minima);resource(items.getValue()[1],R.string.l10n_dex_maker_funding_mxusd);
                choose.getValue().onClick(null,selected);
                assertEquals(2,dialogs.constructed().size());LanguageDialog confirmation=dialogs.constructed().get(1);
                ArgumentCaptor<CharSequence> body=ArgumentCaptor.forClass(CharSequence.class);verify(confirmation).setMessage(body.capture());
                verify(confirmation,never()).setItems(any(CharSequence[].class),any());
                Object choice=one(body.getValue(),selected==0?R.string.l10n_dex_maker_funding_minima:R.string.l10n_dex_maker_funding_mxusd);
                assertArrayEquals(selected==0?new Object[]{"12.34","1"}:new Object[]{"56.78","2"},args(choice));
                assertArrayEquals(new Object[]{SelfSplit.COUNT},args(one(body.getValue(),R.string.l10n_dex_maker_split_body)));
                verify(confirmation).setPositiveButton(any(CharSequence.class),any(DialogInterface.OnClickListener.class));
                verify(confirmation).setNegativeButton(any(CharSequence.class),isNull());
                // The test stops at review. No node, wallet, activity lifecycle or transaction is started.
            }
        }
    }
    @Test public void storageAndUnknownSplitFailuresCannotLoseTheirWarnings()throws Exception {
        Object failed=DexMessages.message("Maker settings could not be saved. Check available storage. Publishing was not started.");
        resource(failed,R.string.l10n_dex_maker_publish_not_started);resource(args(failed)[0],R.string.l10n_dex_maker_save_failed);
        Object conflict=DexMessages.message("Maker settings changed in another session. Reload and review them before saving again. No cancellation started; saved settings may still be enabled.");
        resource(conflict,R.string.l10n_dex_maker_cancel_not_started);resource(args(conflict)[0],R.string.l10n_dex_maker_settings_conflict);
        String raw="node detail\nSplit submitted — waiting for spendable wallet coins\n0x123 中文 $1 {amount}";
        for(String prefix:new String[]{"Split failed - ","Split failed — ","Split failed: "}) {
            Object error=DexMessages.message(prefix+raw);resource(error,R.string.l10n_dex_maker_split_failed);assertArrayEquals(new Object[]{raw},args(error));
        }
        resource(DexMessages.message("Split outcome unknown — review Assets"),R.string.l10n_dex_maker_split_unknown);
    }
}
