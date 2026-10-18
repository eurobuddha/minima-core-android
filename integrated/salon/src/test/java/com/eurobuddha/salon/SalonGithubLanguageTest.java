package com.eurobuddha.salon;

import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonGithubLanguageTest {
    @Test public void typedGithubFailuresRemainTranslatedInsidePublicationStatus()throws Exception {
        Language.initialize(RuntimeEnvironment.getApplication(),()->false);MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Method error=MainActivity.class.getDeclaredMethod("mediaError",Exception.class);error.setAccessible(true);
        GithubUploader.ProfileError[] reasons=GithubUploader.ProfileError.values();int[] ids={l10n_salon_github_configuration,l10n_salon_github_read,l10n_salon_github_invalid,l10n_salon_github_conflict,l10n_salon_github_write,l10n_salon_github_unavailable};
        for(int i=0;i<reasons.length;i++){
            GithubUploader.ProfileFailure failure=new GithubUploader.ProfileFailure(reasons[i],403);CharSequence detail=(CharSequence)error.invoke(activity,failure);
            TextView status=new TextView(activity);Language.setText(status,Language.msg(l10n_salon_publish_failed,detail));
            for(String code:new String[]{"en","zh-Hans","ru","uk"}){
                Language.select(activity,code);CharSequence expected=Language.msg(ids[i],403);
                assertEquals(expected.toString(),detail.toString());assertEquals(Language.msg(l10n_salon_publish_failed,expected).toString(),status.getText().toString());
            }
        }
        Field io=MainActivity.class.getDeclaredField("io");io.setAccessible(true);((ExecutorService)io.get(activity)).shutdownNow();Language.select(activity,"en");
    }
}
