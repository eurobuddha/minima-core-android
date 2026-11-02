package com.eurobuddha.pandapools;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class CombinedBackupStateTest {
 @Test public void failedCommitCannotSatisfyBackupGateViaPreferencesMemory(){
  CollectConfirmationTest f=new CollectConfirmationTest();f.setup();
  // This harness deliberately applies edits to memory even on failed commit, as Android does.
  Pool pool=new Pool();pool.address=CollectConfirmationTest.id('e');List<Pool> pools=new ArrayList<>();pools.add(pool);
  // Use a direct fluent editor so putLong participates in the same commit transaction.
  android.content.SharedPreferences.Editor edit=mock(android.content.SharedPreferences.Editor.class);
  when(f.prefs.edit()).thenReturn(edit);
  when(edit.putString(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(i->{f.values.put(i.getArgument(0),i.getArgument(1));return edit;});
  when(edit.putLong(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong())).thenReturn(edit);
  when(edit.putInt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(edit);
  when(edit.commit()).thenAnswer(i->f.commitSucceeds);
  try(MockedStatic<OwnPoolStore> own=mockStatic(OwnPoolStore.class)){
   own.when(()->OwnPoolStore.all(f.context)).thenReturn(pools);
   f.commitSucceeds=false;assertFalse(BackupState.recordCombinedExport(f.context,pools));assertFalse(BackupState.combinedUpToDate(f.context));
   f.commitSucceeds=true;assertTrue(BackupState.recordCombinedExport(f.context,pools));assertTrue(BackupState.combinedUpToDate(f.context));
   Pool newer=new Pool();newer.address=CollectConfirmationTest.id('f');pools.add(newer);assertFalse(BackupState.combinedUpToDate(f.context));
  }
 }
}
