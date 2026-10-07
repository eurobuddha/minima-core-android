package com.eurobuddha.entropy;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

/** A tab page: a ScrollView container filled programmatically (casino pattern). */
public abstract class BaseView {

    protected final MainActivity act;
    private final View root;
    protected final LinearLayout container;

    protected BaseView(MainActivity a) {
        act = a;
        root = LayoutInflater.from(a).inflate(R.layout.pm_entropy_view_container, null);
        container = root.findViewById(R.id.pm_entropy_container);
    }

    public View getRoot() { return root; }

    public abstract void refresh();

    public void onShown() {}
}
