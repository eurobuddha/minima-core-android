package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.util.Base64;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import com.eurobuddha.minimacore.i18n.Language;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.*;
import org.junit.Test;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real biometric continuations with fake platform prompts/crypto; never accesses a device key. */
public class WalletBiometricSessionTest {
    static class Fixture implements AutoCloseable {
        final FragmentActivity activity=mock(FragmentActivity.class);
        final Lifecycle lifecycle=mock(Lifecycle.class);
        final Context app=mock(Context.class);
        final SharedPreferences prefs=mock(SharedPreferences.class);
        final SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class,RETURNS_SELF);
        final Cipher cipher=mock(Cipher.class);
        final KeyStore store=mock(KeyStore.class);
        final KeyGenerator generator=mock(KeyGenerator.class);
        final AtomicBoolean allowed=new AtomicBoolean(true);
        final Map<String,String> stored=new java.util.concurrent.ConcurrentHashMap<>();
        final List<BiometricPrompt.AuthenticationCallback> replies=new ArrayList<>();
        final List<AutoCloseable> mocks=new ArrayList<>();
        Fixture()throws Exception{
            when(activity.getLifecycle()).thenReturn(lifecycle);when(lifecycle.getCurrentState()).thenReturn(Lifecycle.State.RESUMED);
            when(activity.getApplicationContext()).thenReturn(app);when(app.getSharedPreferences(anyString(),anyInt())).thenReturn(prefs);
            when(prefs.edit()).thenReturn(editor);when(prefs.getString(anyString(),isNull())).thenReturn("synthetic ciphertext");
            doAnswer(i->{stored.put(i.getArgument(0),"fixture");return editor;}).when(editor).putString(anyString(),nullable(String.class));
            doAnswer(i->{stored.remove(i.getArgument(0));return editor;}).when(editor).remove(anyString());
            when(generator.generateKey()).thenReturn(mock(SecretKey.class));when(store.getKey(anyString(),isNull())).thenReturn(mock(SecretKey.class));
            when(cipher.doFinal(any(byte[].class))).thenReturn(new byte[]{7,8});when(cipher.getIV()).thenReturn(new byte[]{9});
            MockedStatic<KeyStore> stores=mockStatic(KeyStore.class);mocks.add(stores);stores.when(()->KeyStore.getInstance(anyString())).thenReturn(store);
            MockedStatic<KeyGenerator> keys=mockStatic(KeyGenerator.class);mocks.add(keys);keys.when(()->KeyGenerator.getInstance(anyString(),anyString())).thenReturn(generator);
            MockedStatic<Cipher> ciphers=mockStatic(Cipher.class);mocks.add(ciphers);ciphers.when(()->Cipher.getInstance(anyString())).thenReturn(cipher);
            MockedStatic<Base64> base64=mockStatic(Base64.class);mocks.add(base64);base64.when(()->Base64.encodeToString(any(),anyInt())).thenReturn("encrypted fixture");base64.when(()->Base64.decode(anyString(),anyInt())).thenReturn(new byte[]{1});
            MockedStatic<Language> language=mockStatic(Language.class);mocks.add(language);language.when(()->Language.text(any(),anyInt(),any(Object[].class))).thenReturn("fixture");
            MockedStatic<ContextCompat> compat=mockStatic(ContextCompat.class);mocks.add(compat);compat.when(()->ContextCompat.getMainExecutor(any())).thenReturn((Executor)Runnable::run);
            mocks.add(mockConstruction(KeyGenParameterSpec.Builder.class,withSettings().defaultAnswer(RETURNS_SELF),(b,c)->when(b.build()).thenReturn(mock(KeyGenParameterSpec.class))));
            mocks.add(mockConstruction(BiometricPrompt.PromptInfo.Builder.class,withSettings().defaultAnswer(RETURNS_SELF),(b,c)->when(b.build()).thenReturn(mock(BiometricPrompt.PromptInfo.class))));
            mocks.add(mockConstruction(BiometricPrompt.class,(b,c)->replies.add((BiometricPrompt.AuthenticationCallback)c.arguments().get(2))));
        }
        void succeed(int index){
            BiometricPrompt.AuthenticationResult result=mock(BiometricPrompt.AuthenticationResult.class,RETURNS_DEEP_STUBS);
            when(result.getCryptoObject().getCipher()).thenReturn(cipher);replies.get(index).onAuthenticationSucceeded(result);
        }
        @Override public void close()throws Exception{Collections.reverse(mocks);for(AutoCloseable m:mocks)m.close();}
    }
    @Test public void newerEnableRequestRejectsOldSuccessAndErrors()throws Exception{
        try(Fixture f=new Fixture()){
            BiometricUnlock.Callback old=mock(BiometricUnlock.Callback.class),current=mock(BiometricUnlock.Callback.class);
            BiometricUnlock.enable(f.activity,"old fixture",f.allowed::get,old);
            BiometricUnlock.enable(f.activity,"new fixture",f.allowed::get,current);assertEquals(2,f.replies.size());
            f.succeed(0);f.replies.get(0).onAuthenticationError(1,"fixture error");
            verify(f.cipher,never()).doFinal(any());verify(f.editor,never()).putString(anyString(),anyString());verifyNoInteractions(old);
            f.succeed(1);verify(current).onSuccess();verify(f.editor,times(2)).putString(anyString(),anyString());
        }
    }
    @Test public void disabledOrRevokedUnlockCannotDecryptOrDeliverPassword()throws Exception{
        try(Fixture f=new Fixture()){
            BiometricUnlock.UnlockCallback callback=mock(BiometricUnlock.UnlockCallback.class);
            BiometricUnlock.unlock(f.activity,f.allowed::get,callback);assertEquals(1,f.replies.size());
            f.allowed.set(false);f.succeed(0);verify(f.cipher,never()).doFinal(any());verifyNoInteractions(callback);
            f.allowed.set(true);BiometricUnlock.disable(f.activity);f.succeed(0);
            verify(f.cipher,never()).doFinal(any());verifyNoInteractions(callback);
        }
    }
    @Test public void pausedOrDestroyedActivityCannotStoreCredential()throws Exception{
        try(Fixture f=new Fixture()){
            BiometricUnlock.Callback callback=mock(BiometricUnlock.Callback.class);
            BiometricUnlock.enable(f.activity,"fixture",f.allowed::get,callback);assertEquals(1,f.replies.size());
            when(f.lifecycle.getCurrentState()).thenReturn(Lifecycle.State.STARTED);f.succeed(0);
            when(f.lifecycle.getCurrentState()).thenReturn(Lifecycle.State.RESUMED);when(f.activity.isDestroyed()).thenReturn(true);f.succeed(0);
            verify(f.cipher,never()).doFinal(any());verify(f.editor,never()).putString(anyString(),anyString());verifyNoInteractions(callback);
        }
    }
    @Test public void concurrentInvalidationCannotBeUndoneByAnInFlightSuccess()throws Exception{
        try(Fixture f=new Fixture()){
            BiometricUnlock.Callback callback=mock(BiometricUnlock.Callback.class);
            BiometricUnlock.enable(f.activity,"fixture",f.allowed::get,callback);
            java.util.concurrent.CountDownLatch encrypting=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),invalidating=new java.util.concurrent.CountDownLatch(1),disabled=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            when(f.cipher.doFinal(any(byte[].class))).thenAnswer(i->{encrypting.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return new byte[]{7};});
            Thread success=new Thread(()->{try{f.succeed(0);}catch(Throwable e){failure.set(e);}});
            Thread disable=new Thread(()->{
                try(MockedStatic<KeyStore> store=mockStatic(KeyStore.class)){
                    store.when(()->KeyStore.getInstance(anyString())).thenReturn(f.store);
                    invalidating.countDown();BiometricUnlock.disable(f.activity);disabled.countDown();
                }catch(Throwable e){failure.set(e);}
            });
            try{
                success.start();assertTrue(encrypting.await(5,java.util.concurrent.TimeUnit.SECONDS));
                disable.start();assertTrue(invalidating.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(disabled.await(150,java.util.concurrent.TimeUnit.MILLISECONDS));
            }finally{release.countDown();success.join(5000);disable.join(5000);}
            assertFalse(success.isAlive());assertFalse(disable.isAlive());assertNull(failure.get());
            assertEquals(0,disabled.getCount());assertTrue(f.stored.isEmpty());
        }
    }
}
