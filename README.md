# minima-core-android

Reviewed release artifacts: **Minima Core 1.9.10 (81)**. Latest verified Fold installation: **1.9.9 (80)**. The app name is Minima Core;
PandaBear, BlackBear and Pandamonium are build names. Their Android IDs are
`com.eurobuddha.minimacore`, `com.eurobuddha.minimablock` and `com.eurobuddha.pandamonium`.
The shared SDK lives under `com.eurobuddha.minimaapi` and uses the `com.eurobuddha.minimacore`
IPC action prefix. Standalone companions remain supported alongside embedded apps.

Pandamonium 1.9.9 is installed on the owner’s Fold and was confirmed working. It includes the
History token-name fix, compact Core toolbar, persistent long-hold sidebar reordering, MinimaMail
and Minima Vestr. Store publication is pending the owner’s selection.

Version 1.9.10 separates the node wallet’s own balance from companion watch addresses.
`coins relevant:true` still includes tracked coins; `coins own:true` selects owned addresses
before applying a result limit. See [the 1.9.10 review](review/2026-10-07/CORE_1.9.10_REVIEW.md).

Changing the core identity requires data migration;
keep the previous installation until recovery has been verified. See
[the build differences](PANDABEAR_BLACKBEAR.md) and
[namespace migration validation](review/2026-10-07/NAMESPACE_MIGRATION_REVIEW.txt).
Historical releases, reports, backups and Git history must be preserved: the user explicitly prohibited purging.
Historical handoffs describe their original releases and must not be used to restore superseded package IDs.

An Android application running minima-core 

This is a simple clean Minima client that runs in full on Android

Fully non-custodial with wallet functionality

You also have the miniaapi.aar - an Android lib that allows your own applications to talk to Minima Core.

Simply load the minimaapi.aar module/lib into your project.

The main API is accessed via
```
        //You must first register to allow messages to be sent and push notifications
        mMinimaAPI = new MinimaAPI(this, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                MinimaAPILogger.log(zResponse.toString());
            }
        });

        //Run a Minima command
        mMinimaAPI.Command("block", new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                
                //You can now use the JSON zResponse object..
                //..
                        
                //If you want to update a UI component run it on the UI Thread..
                //MainActivity.this.runOnUiThread(new Runnable() {
                //    @Override
                //    public void run() {}
                //});
            }
        });
```

The application shows up in Minima-Core and must be enabled by the User

You MUST call onDestroy from the MinimaAPI to shutdown cleanly
```
    @Override
    protected void onDestroy() {
        super.onDestroy();

        mMinimaAPI.onDestroy();
    }
```

You can receive push notifications of Minima events by creating a BroadcastReceiver in your app and listening for
```
com.eurobuddha.minimacore.NOTIFY
```

Look at the Terminal APK for an example of how this works..
