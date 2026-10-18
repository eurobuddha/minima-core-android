package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
/** Exercise the donor parsers, distinguishing absent metadata from literal app-like names. */
public class UtxoTokenNamesTest {
    private JSONObject name(Object value) throws Exception { return new JSONObject().put("name",value); }
    private JSONObject row(Object token) throws Exception {
        JSONObject row=new JSONObject().put("tokenid","0x1234").put("amount","0.0001").put("tokenamount","12.340000");
        if(token!=null) row.put("token",token); return row;
    }
    @Test public void authoredNamesRemainLiteralAcrossBothModels() throws Exception {
        for(String literal:new String[]{"Token","Send","中文 Русский Українська {amount} $1","[1,2]"})
            for(Object token:new Object[]{literal,name(literal),name(name(literal))}) {
                Coin coin=Coin.from(row(token));TokenBalance balance=TokenBalance.from(row(token));
                assertTrue(coin.tokenNameProvided);assertTrue(balance.meta.nameProvided);
                assertEquals(literal,coin.tokenName);assertEquals(literal,balance.name);
                assertEquals(literal,UtxoText.token(coin));assertEquals(literal,UtxoText.token(balance));
                assertEquals("12.340000",coin.amount);
            }
    }
    @Test public void absentNamesUseBindingsWithoutChangingRawMetadata() throws Exception {
        for(Object token:new Object[]{null,"",new JSONObject(),name(new JSONObject()),name(""),name(JSONObject.NULL)}) {
            Coin coin=Coin.from(row(token));TokenBalance balance=TokenBalance.from(row(token));
            String rawCoin=coin.tokenName, rawBalance=balance.name;
            assertFalse(coin.tokenNameProvided);assertFalse(balance.meta.nameProvided);
            assertTrue(UtxoText.token(coin) instanceof Language.Message);
            assertTrue(UtxoText.token(balance) instanceof Language.Message);
            assertEquals(rawCoin,coin.tokenName);assertEquals(rawBalance,balance.name);
        }
    }
    @Test public void nativeNameAndStructuredMetadataStayIntact() throws Exception {
        JSONObject nativeRow=row(null).put("tokenid","0x00");
        assertEquals("Minima",UtxoText.token(Coin.from(nativeRow)));
        assertEquals("Minima",UtxoText.token(TokenBalance.from(nativeRow)));
        JSONObject meta=name("Token").put("ticker","SEND").put("description","Send Русский Українська $1")
                .put("owner","public owner").put("external_url","https://example.invalid/token%20name");
        TokenMeta parsed=TokenMeta.parse(name(meta).put("decimals","8"),"0x1234");
        assertEquals("Token",parsed.name);assertEquals("SEND",parsed.ticker);
        assertEquals("Send Русский Українська $1",parsed.description);assertEquals("public owner",parsed.owner);
        assertEquals("8",parsed.decimals);assertEquals("https://example.invalid/token name",parsed.externalUrl);
    }

    @Test public void serializedMetadataUsesTheCoreBalanceNameAndIcon() throws Exception {
        String image = "data:image/png;base64," + String.join("", java.util.Collections.nCopies(32768, "AAAA"));
        JSONObject details=name("Колекція 中文").put("url",image).put("ticker","NFT");
        for (Object token : new Object[]{details.toString(), name(details).toString(), name(details.toString()), name(details.toString()).toString()}) {
            JSONObject input=row(token); String before=input.toString();
            Coin coin=Coin.from(input); TokenBalance balance=TokenBalance.from(input);
            assertEquals("Колекція 中文",coin.tokenName);
            assertEquals("Колекція 中文",balance.name);
            assertTrue(coin.tokenNameProvided); assertTrue(balance.meta.nameProvided);
            assertEquals(image,balance.meta.iconUrl); assertEquals("NFT",balance.meta.ticker);
            assertEquals("12.340000",coin.amount); assertEquals(before,input.toString());
        }
    }
    @Test public void serializedMissingNamesDoNotBecomeJsonLabels() throws Exception {
        for (Object token : new Object[]{"{}", name(new JSONObject()).toString(), name("{}"), name(JSONObject.NULL).toString()}) {
            assertFalse(TokenMeta.parse(token,"0x1234").nameProvided);
            assertFalse(Coin.from(row(token)).tokenNameProvided);
        }
        assertEquals("{unfinished",TokenMeta.parse("{unfinished","0x1234").name);
    }
}
