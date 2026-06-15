package org.minimarex.minimacore.main.views.apps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.BaseView;
import org.minimarex.minimacore.receiver.ReceiverDB;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.logger;

public class AppsView extends BaseView {

    ListView mAppsList;
    AppsAdapter mAppsAdapter;

    public AppsView(Activity zActivity){
        super(zActivity, R.layout.view_apps);

        mAppsAdapter = new AppsAdapter(zActivity);

        mAppsList = getMainView().findViewById(R.id.wallet_apps_list);
        mAppsList.setAdapter(mAppsAdapter);

        mAppsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

                //Get the selecetd app
                JSONObject app = (JSONObject) mAppsAdapter.getItem(position);

                showInfoDialog(app);
            }
        });
    }

    public void showInfoDialog(JSONObject zApp){

        LayoutInflater inflater = (LayoutInflater) mActivity.getSystemService(mActivity.LAYOUT_INFLATER_SERVICE);
        View dialogview = inflater.inflate(R.layout.dialog_apps_info, null);

        final String packageclass = (String)zApp.get("package");
        TextView packagec   = dialogview.findViewById(R.id.app_info_package);
        packagec.setText(packageclass);

        TextView name       = dialogview.findViewById(R.id.app_info_name);
        name.setText("not found..");

        final CheckBox cb = dialogview.findViewById(R.id.app_info_enabled);
        if((int)zApp.get("penabled") == 1){
            cb.setChecked(true);
        }

        cb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mAppsAdapter.getDatabase().setEnabled((String)zApp.get("package"), cb.isChecked());
            }
        });

        //Get the ICON

        try{
            PackageManager pkgmanager = mActivity.getPackageManager();

            ApplicationInfo appinfo = pkgmanager.getApplicationInfo(packageclass, 0);

            String pname     = pkgmanager.getApplicationLabel(appinfo).toString();
            name.setText(pname);

            //Drawable icon   = pkgmanager.getApplicationIcon(appinfo);
            //img.setImageDrawable(icon);

        }catch (PackageManager.NameNotFoundException e){
            logger.log("Package not found.. "+packageclass+" "+e);
        }

        new AlertDialog.Builder(getActivity())
                .setTitle("Application Info")
                .setView(dialogview)
                .setIcon(R.drawable.ic_minima)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {
                        AppsView.this.refreshView();
                    }}).show();

    }

    public void setDatabase(ReceiverDB zDB){
        mAppsAdapter.setDatabase(zDB);
    }

    @Override
    public void refreshView() {
        mAppsAdapter.updateValues();
    }
}
