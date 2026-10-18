package com.eurobuddha.salon;

import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonPageLanguageTest {
    @Test public void publishedHtmlUsesExplicitSelectionAndEmbedsAllLanguages()throws Exception{
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);
        Method html=MainActivity.class.getDeclaredMethod("salonHtml");html.setAccessible(true);
        for(String code:new String[]{"en","zh-Hans","ru","uk"}){
            Language.select(RuntimeEnvironment.getApplication(),code);String page=(String)html.invoke(null);
            assertTrue(page.contains("data-salon-language=\""+code+"\""));
            for(String name:new String[]{"English","简体中文","Русский","Українська"})assertTrue(page.contains(name));
            assertFalse(page.contains("__SALON_"));assertFalse(page.contains("navigator.language"));
            assertFalse(page.contains("<script src="));assertTrue(page.contains("fetch('./profile.json')"));
        }
    }
}
