package com.eurobuddha.statenft;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual bridge refresh/lifecycle code with the Android WebView boundary isolated. */
public class FiltrLanguageBridgeTest {
    private static void set(FiltrActivity activity,String name,Object value) throws Exception {
        Field f=FiltrActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);
    }
    private static Object get(FiltrActivity activity,String name) throws Exception {
        Field f=FiltrActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    private static void refresh(FiltrActivity activity) throws Exception {
        Method m=FiltrActivity.class.getDeclaredMethod("refreshLanguage");m.setAccessible(true);m.invoke(activity);
    }
    @Test public void languageSelectionUpdatesExistingPageWithoutReloadOrHandoff() throws Exception {
        FiltrActivity activity=mock(FiltrActivity.class,CALLS_REAL_METHODS);WebView web=mock(WebView.class);set(activity,"web",web);
        when(web.getUrl()).thenReturn("file:///android_asset/filtr/filtr.html");
        String mint=FiltrActivity.pendingMint,photo=FiltrActivity.pendingPhoto;
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            for(String code:new String[]{"en","zh-Hans","ru","uk"}) {
                language.when(Language::code).thenReturn(code);refresh(activity);
                verify(web).evaluateJavascript("window.FiltrI18n && FiltrI18n.setLanguage(\""+code+"\");",null);
            }
        }
        verify(web,never()).loadUrl(anyString());verify(web,never()).reload();verify(web,never()).destroy();
        assertSame(mint,FiltrActivity.pendingMint);assertSame(photo,FiltrActivity.pendingPhoto);
    }
    @Test public void staleOrForeignPagesNeverReceiveBridgeUpdates() throws Exception {
        FiltrActivity activity=mock(FiltrActivity.class,CALLS_REAL_METHODS);WebView web=mock(WebView.class);set(activity,"web",web);
        for(String url:new String[]{null,"https://example.invalid/","file:///other.html"}) {
            when(web.getUrl()).thenReturn(url);refresh(activity);
        }
        verify(web,never()).evaluateJavascript(anyString(),any());
    }
    @Test public void destructionRemovesListenerCancelsPickerAndReleasesWebView() throws Exception {
        FiltrActivity activity=mock(FiltrActivity.class,CALLS_REAL_METHODS);WebView web=mock(WebView.class);Runnable listener=mock(Runnable.class);
        @SuppressWarnings("unchecked") ValueCallback<Uri[]> picker=mock(ValueCallback.class);
        set(activity,"web",web);set(activity,"languageChanged",listener);set(activity,"filePick",picker);
        try(MockedStatic<Language> language=mockStatic(Language.class)) {
            activity.onDestroy();language.verify(()->Language.removeListener(listener));
        }
        verify(picker).onReceiveValue(null);verify(web).removeJavascriptInterface("AndroidFiltr");verify(web).destroy();
        assertNull(get(activity,"web"));assertNull(get(activity,"filePick"));
        refresh(activity);verify(web,never()).evaluateJavascript(anyString(),any());
    }
}
