package com.eurobuddha.salon;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackupValidationTest {
    private JSONObject file() throws Exception { return new JSONObject().put("salonBackup",1); }
    private JSONObject host(String id) throws Exception {
        return new JSONObject().put("id",id).put("type","sftp").put("name","中文 Русский Українська")
                .put("sftp",new JSONObject().put("password","literal password"));
    }
    @Test public void canonicalVersionAndOptionalLegacyFieldsRemainCompatible() throws Exception {
        JSONObject b=file().put("me",new JSONObject().put("name","Literal 中文 Русский Українська")
                .put("links",new JSONArray().put(new JSONObject().put("label","author text"))))
                .put("hosting",new JSONArray().put(host("first"))).put("futureField",new JSONObject().put("value",17));
        String original=b.toString();BackupValidation.check(b);assertEquals(original,b.toString());BackupValidation.check(file());
    }
    @Test public void versionMustBeTheSupportedIntegerNotACoercedStringOrFraction() throws Exception {
        for(Object value:new Object[]{"1",1.5,2,true,JSONObject.NULL}){
            JSONObject b=file().put("salonBackup",value);assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
        }
    }
    @Test public void nestedProfileTypesAndArrayEntriesAreValidatedBeforeApplying() throws Exception {
        for(String key:new String[]{"handle","tokenid","profileUrl","name","bio","msgpk","msgseed"}){
            JSONObject b=file().put("me",new JSONObject().put(key,new JSONObject()));assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
        }
        for(String key:new String[]{"links","gallery","posts","nfts","follows"})for(Object value:new Object[]{"[]",new JSONArray().put("invalid"),JSONObject.NULL}){
            JSONObject b=file().put("me",new JSONObject().put(key,value));assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
        }
    }
    @Test public void missingOrRepeatedHostIdsCannotSilentlyReplaceAnotherDestination() throws Exception {
        for(Object bad:new Object[]{"",JSONObject.NULL,17}){
            JSONObject b=file().put("hosting",new JSONArray().put(host("first")).put(host("second").put("id",bad)));
            assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
        }
        JSONObject b=file().put("hosting",new JSONArray().put(host("same")).put(host("same")));
        assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
    }
    @Test public void inactiveHostingConfigAndBooleanFieldsCannotBeSilentlyCoerced() throws Exception {
        for(JSONObject host:new JSONObject[]{host("one").put("github","broken"),host("one").put("def","true"),host("one").put("name",42),host("one").put("type","")}){
            JSONObject b=file().put("hosting",new JSONArray().put(host));assertThrows(IllegalArgumentException.class,()->BackupValidation.check(b));
        }
    }
}
