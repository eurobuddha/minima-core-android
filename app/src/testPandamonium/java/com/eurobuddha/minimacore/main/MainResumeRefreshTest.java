package com.eurobuddha.minimacore.main;
import android.app.Application;
import androidx.viewpager.widget.ViewPager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class,shadows=MainResumeRefreshTest.FragmentHost.class)
public class MainResumeRefreshTest {
 // Isolate the real host resume method from FragmentManager setup and node startup.
 @org.robolectric.annotation.Implements(androidx.fragment.app.FragmentActivity.class)
 public static class FragmentHost extends org.robolectric.shadows.ShadowActivity {
  @org.robolectric.annotation.Implementation protected void onResume() {}
 }
 @Test public void resumeRefreshesAlreadyActiveSelectedWallet()throws Exception {
  MainActivity a=Robolectric.buildActivity(MainActivity.class).get();
  MainAdapter adapter=mock(MainAdapter.class);
  java.lang.reflect.Field field=MainActivity.class.getDeclaredField("mMainAdapter");field.setAccessible(true);field.set(a,adapter);
  field=MainActivity.class.getDeclaredField("mMainPager");field.setAccessible(true);field.set(a,new ViewPager(a));
  field=MainActivity.class.getDeclaredField("hostVisible");field.setAccessible(true);field.set(a,true);
  a.onResume();
  verify(adapter).setHostVisible(true,MainAdapter.WALLET_POSITION);
  verify(adapter).refreshPagerView(MainAdapter.WALLET_POSITION);
 }
}
