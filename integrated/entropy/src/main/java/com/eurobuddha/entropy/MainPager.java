package com.eurobuddha.entropy;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.PagerAdapter;

public class MainPager extends PagerAdapter {

    private final BaseView[] views;
    private final CharSequence[] titles;

    public MainPager(BaseView[] views, CharSequence[] titles) {
        this.views = views;
        this.titles = titles;
    }

    public BaseView viewAt(int pos) { return views[pos]; }

    @Override public int getCount() { return views.length; }

    @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object o) { return view == o; }

    @NonNull @Override public Object instantiateItem(@NonNull ViewGroup parent, int pos) {
        View v = views[pos].getRoot();
        parent.addView(v);
        return v;
    }

    @Override public void destroyItem(@NonNull ViewGroup parent, int pos, @NonNull Object o) {
        parent.removeView((View) o);
    }

    @Override public CharSequence getPageTitle(int pos) { return titles[pos]; }
}
