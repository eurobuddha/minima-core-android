    private final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();

    private void saveImage(String name,String b64) {
        if(handoffClosed||!saving.compareAndSet(false,true))return;
        final android.content.Context ctx=getApplicationContext();
        try{ArtworkExport.execute(()->{
            boolean saved=false;
            try{
                ArtworkExport.check(()->handoffClosed);
                ArtworkExport.Encoded image=ArtworkExport.filtr(b64);
                String base=name==null?"filtr":name.replaceFirst("\\.[^.]*$","");
                ArtworkExport.save(ctx,ArtworkExport.filename(base,image.extension),image.mime,
                        out->out.write(image.bytes),()->handoffClosed);
                saved=true;
            }catch(Exception failure){}
            final boolean success=saved;
            runOnUiThread(()->exportFinished(success));
        });}catch(java.util.concurrent.RejectedExecutionException full){runOnUiThread(()->exportFinished(false));}
    }
    private void exportFinished(boolean success) {
        saving.set(false);
        if(handoffClosed||isFinishing()||isDestroyed())return;
        Toast.makeText(this,Language.msg(success
                ?com.eurobuddha.minimacore.i18n.R.string.l10n_creative_saved_photos
                :com.eurobuddha.minimacore.i18n.R.string.l10n_creative_save_image_failed),Toast.LENGTH_SHORT).show();
        if(web!=null&&"file:///android_asset/filtr/filtr.html".equals(web.getUrl()))
            web.evaluateJavascript("filtrSaveFinished("+success+");",null);
    }

