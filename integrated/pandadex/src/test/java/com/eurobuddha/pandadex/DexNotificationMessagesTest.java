package com.eurobuddha.pandadex;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.R;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DexNotificationMessagesTest {
    private static Object field(Object value,String name)throws Exception {
        assertTrue(value instanceof Language.Message);Field f=Language.Message.class.getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static Object[] args(Object value)throws Exception{return (Object[])field(value,"arguments");}
    private static void resource(Object value,int id)throws Exception{assertEquals(id,field(value,"resource"));}
    @Test public void actualFillNotifierKeepsDirectionPartialTitleAndExactAmounts()throws Exception {
        for(boolean sold:new boolean[]{false,true})for(boolean partial:new boolean[]{false,true}) {
            Context context=mock(Context.class);NotificationManager manager=mock(NotificationManager.class);Notification notification=mock(Notification.class);
            when(context.getSystemService(NotificationManager.class)).thenReturn(manager);
            try(MockedStatic<PendingIntent> pending=mockStatic(PendingIntent.class);
                MockedConstruction<Intent> intents=mockConstruction(Intent.class);
                MockedConstruction<NotificationCompat.BigTextStyle> styles=mockConstruction(NotificationCompat.BigTextStyle.class,withSettings().defaultAnswer(RETURNS_SELF));
                MockedConstruction<NotificationCompat.Builder> builders=mockConstruction(NotificationCompat.Builder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,ctx)->when(builder.build()).thenReturn(notification))) {
                Notifier.fill(context,sold,new BigDecimal("12.34000"),new BigDecimal("0.001234"),partial);
                assertEquals(1,builders.constructed().size());NotificationCompat.Builder builder=builders.constructed().get(0);
                ArgumentCaptor<CharSequence> title=ArgumentCaptor.forClass(CharSequence.class),body=ArgumentCaptor.forClass(CharSequence.class),expanded=ArgumentCaptor.forClass(CharSequence.class);
                verify(builder).setContentTitle(title.capture());verify(builder).setContentText(body.capture());
                resource(title.getValue(),partial?R.string.l10n_dex_notice_partial:R.string.l10n_dex_notice_filled);
                resource(body.getValue(),sold?R.string.l10n_dex_notice_sold:R.string.l10n_dex_notice_bought);
                assertArrayEquals(new Object[]{"12.34","0.001234"},args(body.getValue()));
                assertEquals(1,styles.constructed().size());verify(styles.constructed().get(0)).bigText(expanded.capture());
                assertArrayEquals(args(body.getValue()),args(expanded.getValue()));
                verify(manager).notify(anyInt(),same(notification));verify(builder).setAutoCancel(true);
            }
        }
    }
    @Test public void confirmedCreationAndConfirmingProceedsRemainDifferent()throws Exception {
        for(String side:new String[]{"Buy","Sell"}) {
            String order=side+" 00012.34000 MINIMA @ 0.001234";
            Object created=DexMessages.message(order+" was created on-chain");resource(created,R.string.l10n_dex_notice_created_body);
            resource(args(created)[0],side.equals("Buy")?R.string.l10n_dex_notice_buy_order:R.string.l10n_dex_notice_sell_order);
            assertArrayEquals(new Object[]{"00012.34000","0.001234"},args(args(created)[0]));
            Object stage=DexMessages.message(order+" — creation verified on-chain. Check Orders for its current status.");resource(stage,R.string.l10n_dex_notice_created_stage);
        }
        for(String verb:new String[]{"Bought","Sold"}) {
            Object confirming=DexMessages.message(verb+" 12.34 MINIMA @ 0.001234. Funds are confirming and will show as available shortly.");
            resource(confirming,R.string.l10n_dex_notice_confirming);resource(args(confirming)[0],verb.equals("Bought")?R.string.l10n_dex_notice_bought:R.string.l10n_dex_notice_sold);
            assertArrayEquals(new Object[]{"12.34","0.001234"},args(args(confirming)[0]));
        }
    }
    @Test public void upkeepFailureKeepsPriceAndUnknownDiagnosticWhole()throws Exception {
        String raw="node error\nOrder filled\n0x123 中文 Русский Українська $1 {price}";
        Object notice=DexMessages.message("Order @ 0.001234 — Covenant check failed: "+raw);
        resource(notice,R.string.l10n_dex_notice_order_error);assertEquals("0.001234",args(notice)[0]);
        Object error=args(notice)[1];resource(error,R.string.l10n_dex_notice_covenant_failure);assertArrayEquals(new Object[]{raw},args(error));
        assertEquals(raw,DexMessages.message(raw));
    }
    @Test public void recoveryWarningsRetainEvidenceAndDoNotBecomeTradeSuccess()throws Exception {
        resource(DexMessages.message("A stored trade has conflicting inclusion evidence. Earlier evidence is retained while history reconciliation is pending."),R.string.l10n_dex_history_conflict);
        resource(DexMessages.message("Trade-history recovery is paused by a storage error. Saved candidates are retained; free device storage and reopen PandaDEX."),R.string.l10n_dex_history_storage_paused);
        resource(DexMessages.message("Historical search progress could not be saved. Existing receipts are retained; this search will repeat."),R.string.l10n_dex_history_save_failed);
        resource(DexMessages.message("Order watcher paused"),R.string.l10n_dex_notice_watcher_paused);
    }
}
