package com.eurobuddha.vestr;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 2026-11-22 (r3 V1): the "already waiting for the chain, or already spent" sentence says what to do, in every
 *  language: a txn that is never mined leaves the node's memory within 60 to 90 minutes. */
public class AlreadySpendingTextTest {
    static String text(String values) throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("../../localization/src/main/res/" + values + "/bear_strings.xml")), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<string name=\"l10n_vestr_already_spending\" formatted=\"false\">\"([^\"]*)\"</string>").matcher(xml);
        assertTrue(values, m.find());
        return m.group(1);
    }

    @Test public void theSentenceSaysWhatToDo() throws Exception {
        assertTrue(text("values"), text("values").endsWith("Nothing was sent. Look again after a few blocks; if the contract is still listed after about an hour and a half, collect again."));
        assertTrue(text("values-b+zh+Hans"), text("values-b+zh+Hans").contains("一个半小时"));
        assertTrue(text("values-ru"), text("values-ru").contains("полтора часа"));
        assertTrue(text("values-uk"), text("values-uk").contains("півтори години"));
    }
}
