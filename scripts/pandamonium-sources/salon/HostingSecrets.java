package com.eurobuddha.salon;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pending secret edits stay in this Activity's memory until encryption succeeds. */
final class HostingSecrets {
    private final Map<JSONObject,Map<String,String>> pending = new IdentityHashMap<>();
    private final Map<android.widget.EditText,android.util.Pair<JSONObject,String>> fields = new WeakHashMap<>();
    void bind(android.widget.EditText input,JSONObject cfg,String key) {
        fields.put(input,android.util.Pair.create(cfg,key));
    }
    private void clearError(JSONObject cfg,String key) {
        for(Map.Entry<android.widget.EditText,android.util.Pair<JSONObject,String>> entry:fields.entrySet())
            if(entry.getValue().first==cfg && entry.getValue().second.equals(key))
                com.eurobuddha.minimacore.i18n.Language.error(entry.getKey(),null);
    }
    boolean pending(JSONObject cfg,String key) {
        return pending.containsKey(cfg) && pending.get(cfg).containsKey(key);
    }
    void discard(JSONObject cfg,String key) {
        Map<String,String> fields=pending.get(cfg);
        if(fields!=null){fields.remove(key);if(fields.isEmpty())pending.remove(cfg);}
    }
    String value(JSONObject cfg,String key,boolean revealStored) {
        if(pending(cfg,key)) return pending.get(cfg).get(key);
        return revealStored ? Crypt.decrypt(cfg.optString(key,"")) : "";
    }
    boolean write(JSONObject cfg,String key,String value) {
        try {
            String wrapped=Crypt.encrypt(value);
            cfg.put(key,wrapped);
            Map<String,String> fields=pending.get(cfg);
            if(fields!=null){fields.remove(key);if(fields.isEmpty())pending.remove(cfg);}
            clearError(cfg,key);
            return true;
        } catch(Crypt.Unavailable unavailable) {
            pending.computeIfAbsent(cfg,ignored->new LinkedHashMap<>()).put(key,value);
            return false;
        } catch(org.json.JSONException invalid) {throw new IllegalArgumentException("Invalid secret field");}
    }
    boolean retry(Hosting.Profile profile) {
        boolean ready=true;
        for(JSONObject cfg:new ArrayList<>(pending.keySet())) {
            boolean belongs=false;
            Iterator<String> keys=profile.j.keys();
            while(keys.hasNext())if(profile.j.opt(keys.next())==cfg){belongs=true;break;}
            if(!belongs)continue;
            for(Map.Entry<String,String> field:new LinkedHashMap<>(pending.get(cfg)).entrySet())
                if(!write(cfg,field.getKey(),field.getValue()))ready=false;
        }
        return ready;
    }
    /** Stage every credential before a restore writes any destination or profile draft. */
    static JSONArray prepareRestore(JSONArray input) {
        JSONArray staged=new JSONArray();
        if(input==null)return staged;
        for(int i=0;i<input.length();i++) {
            JSONObject profile=input.optJSONObject(i);
            if(profile==null)throw new IllegalArgumentException("Invalid hosting backup");
            staged.put(copySecrets(profile,true));
        }
        return staged;
    }
    /** Include inactive backend configs as well as the currently selected backend. */
    static JSONObject copySecrets(JSONObject input,boolean encrypt) {
        try {
            JSONObject copy=new JSONObject(input.toString());
            Iterator<String> keys=copy.keys();
            while(keys.hasNext()) {
                JSONObject cfg=copy.optJSONObject(keys.next());
                if(cfg==null)continue;
                for(String key:HostingStore.SECRET_FIELDS) {
                    if(!cfg.has(key))continue;
                    Object raw=cfg.opt(key);
                    if(!(raw instanceof String))throw new IllegalArgumentException("Invalid hosting backup");
                    String value=(String)raw;
                    cfg.put(key,encrypt ? Crypt.encrypt(value) : Crypt.decrypt(value));
                }
            }
            return copy;
        } catch(org.json.JSONException invalid){throw new IllegalArgumentException("Invalid hosting backup");}
    }
    /** Check the selected authentication method before creating a network uploader. */
    static void requireReadable(Hosting.Profile profile) throws Hosting.HostingException {
        String key;
        switch(profile.type()) {
            case "sftp": key="key".equals(profile.cfgStr("auth")) ? "privateKey" : "password";break;
            case "webdav": if(profile.cfgStr("user").isEmpty())return; key="password";break;
            case "kubo": key="password";break;
            case "pinata": key="jwt";break;
            case "github": key="token";break;
            case "arweave": key="jwk";break;
            default:return;
        }
        try {profile.secret(key);}
        catch(Crypt.Unavailable unavailable){throw new Hosting.HostingException(unavailable.getMessage());}
    }
}
