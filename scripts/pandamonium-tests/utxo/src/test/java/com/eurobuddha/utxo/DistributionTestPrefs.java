package com.eurobuddha.utxo;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** NFT lifecycle preference fixture, reused for distribution persistence failures. */
class DistributionTestPrefs {
    static class Prefs {
        final Context ctx=mock(Context.class);final SharedPreferences prefs=mock(SharedPreferences.class);final SharedPreferences.Editor editor=mock(SharedPreferences.Editor.class);
        final Map<String,String> values=new HashMap<>(),changes=new HashMap<>(); boolean writable=true;
        Prefs(){
            when(ctx.getSharedPreferences(eq("nftwallet_statenft"),anyInt())).thenReturn(prefs);
            when(prefs.getString(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
            when(prefs.edit()).thenReturn(editor);
            when(editor.putString(anyString(),anyString())).thenAnswer(i->{changes.put(i.getArgument(0),i.getArgument(1));return editor;});
            when(editor.remove(anyString())).thenAnswer(i->{changes.put(i.getArgument(0),null);return editor;});
            when(editor.commit()).thenAnswer(i->{if(!writable){changes.clear();return false;}flush();return true;});
            doAnswer(i->{flush();return null;}).when(editor).apply();
        }
        void flush(){changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});changes.clear();}
        void rows(JSONObject...rows){JSONArray a=new JSONArray();for(JSONObject r:rows)a.put(r);values.put("collections",a.toString());}
    }
}
