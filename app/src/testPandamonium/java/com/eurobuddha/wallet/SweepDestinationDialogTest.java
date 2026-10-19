package com.eurobuddha.wallet;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.appcompat.app.AlertDialog;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real dialog controls, public fixture and cancellation only; never execute a valid sweep. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class SweepDestinationDialogTest {
    private static final String MX="MxG081PTR905VTUGBKY7KHKV76QVHE99BM691QNC09GP2WPCG1P0QZTHD8SWWVQ";
    private static EditText entry(View view) {
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText found=entry(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }
    private static void put(Object object,String key,Object value)throws Exception {
        Field field=SweepFlow.class.getDeclaredField(key);field.setAccessible(true);field.set(object,value);
    }
    @Test public void finalActionRequiresMatchingAddressAndCancelDoesNotStartSweep()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        // Attach an Activity for real views without starting wallet setup or accessing a vault.
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(com.eurobuddha.wallet.R.style.pm_wallet_Theme_Utxo);
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        put(flow,"act",activity);put(flow,"session",(java.util.function.BooleanSupplier)()->true);
        Method show=SweepFlow.class.getDeclaredMethod("confirmDestination",SweepPlanner.Plan.class,String.class);
        show.setAccessible(true);
        try {
            for(String language:new String[]{"en","zh-Hans","ru","uk"}) {
                Language.select(RuntimeEnvironment.getApplication(),language);
                show.invoke(flow,new SweepPlanner.Plan(),MX);
                AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();
                assertTrue(dialog.isShowing());
                EditText input=entry(dialog.getWindow().getDecorView());assertNotNull(input);
                android.widget.Button execute=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                assertFalse(execute.isEnabled());
                input.setText(MX.substring(0,MX.length()-1)+"0");assertFalse(execute.isEnabled());
                input.setText(MX);assertTrue(execute.isEnabled());
                input.setText("");assertFalse(execute.isEnabled());
                input.setText(MX.toLowerCase(java.util.Locale.ROOT));assertTrue(execute.isEnabled());
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertFalse(dialog.isShowing());
                Field running=SweepFlow.class.getDeclaredField("running");running.setAccessible(true);
                assertFalse(running.getBoolean(flow));
            }
        } finally {Language.select(RuntimeEnvironment.getApplication(),"en");}
    }
    @Test public void destinationOutsideSourceWalletCanReachFinalConfirmation()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        Language.select(RuntimeEnvironment.getApplication(),"en");
        MainActivity activity=spy(Robolectric.buildActivity(MainActivity.class).get());
        activity.setTheme(com.eurobuddha.wallet.R.style.pm_wallet_Theme_Utxo);
        doReturn(null).when(activity).keyIndexForAddress(anyString());
        SweepFlow flow=mock(SweepFlow.class,CALLS_REAL_METHODS);
        put(flow,"act",activity);put(flow,"session",(java.util.function.BooleanSupplier)()->true);
        Method review=SweepFlow.class.getDeclaredMethod("review",SweepPlanner.Plan.class,String.class);
        review.setAccessible(true);review.invoke(flow,new SweepPlanner.Plan(),MX);
        AlertDialog first=(AlertDialog)ShadowDialog.getLatestDialog();assertTrue(first.isShowing());
        verify(activity).keyIndexForAddress(org.minima.objects.Address.convertMinimaAddress(MX).to0xString());
        assertEquals("Confirm destination…",first.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog confirmation=(AlertDialog)ShadowDialog.getLatestDialog();
        assertNotSame(first,confirmation);assertTrue(confirmation.isShowing());
        assertFalse(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        verify(activity,never()).runSigningWork(any(),any(),any());
    }

}
