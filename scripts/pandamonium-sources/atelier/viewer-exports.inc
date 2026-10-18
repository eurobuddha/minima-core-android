    /** A viewer owns its exports; only immutable selection values reach the worker. */
    private static final class Exports {
        final Activity activity; final View root;
        volatile long generation; volatile boolean closed;
        boolean busy;
        Exports(Activity activity,View root) {
            this.activity=activity;this.root=root;
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View v){}
                public void onViewDetachedFromWindow(View v){close();}
            });
        }
        void close(){closed=true;generation++;}
        long select(){return ++generation;}
        boolean current(long selected){return !closed&&selected==generation&&!activity.isFinishing()
                &&!activity.isDestroyed()&&root.isAttachedToWindow();}
        void start(boolean share,long selected,String name,String token,int index,String url){
            if(!current(selected))return;
            if(busy){Toast.makeText(activity,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_atelier_image_busy),Toast.LENGTH_SHORT).show();return;}
            busy=true;
            final Context ctx=activity.getApplicationContext();
            final java.util.function.BooleanSupplier cancelled=()->closed||selected!=generation;
            try{ArtworkExport.execute(()->{
                java.io.File file=null;
                try{
                    ArtworkExport.check(cancelled);
                    Bitmap bitmap=url==null||url.isEmpty()?null:ImageLoader.decode(ctx,url,1800);
                    if(bitmap==null)throw new java.io.IOException("Artwork unavailable");
                    try{
                        if(share)file=ArtworkExport.shareFile(ctx,out->ArtworkExport.jpeg(bitmap,out),cancelled);
                        else ArtworkExport.save(ctx,ArtworkExport.filename(name,"_"+index+".jpg"),"image/jpeg",
                                out->ArtworkExport.jpeg(bitmap,out),cancelled);
                    }finally{bitmap.recycle();}
                    final java.io.File ready=file;
                    activity.runOnUiThread(()->finish(selected,share,name,token,index,ready,true));
                }catch(Exception failure){
                    if(file!=null)file.delete();
                    activity.runOnUiThread(()->finish(selected,share,name,token,index,null,false));
                }
            });}catch(java.util.concurrent.RejectedExecutionException full){finish(selected,share,name,token,index,null,false);}
        }
        void finish(long selected,boolean share,String name,String token,int index,java.io.File file,boolean success){
            busy=false;
            if(!current(selected)){if(file!=null)file.delete();return;}
            if(success&&share){
                try{
                    Uri uri=androidx.core.content.FileProvider.getUriForFile(activity,activity.getPackageName()+".atelier.fileprovider",file);
                    Intent send=new Intent(Intent.ACTION_SEND).setType("image/jpeg");
                    send.putExtra(Intent.EXTRA_STREAM,uri);
                    send.setClipData(ClipData.newRawUri("artwork",uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.putExtra(Intent.EXTRA_TEXT,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_share_lot_caption,name,index,token).toString());
                    activity.startActivity(Intent.createChooser(send,Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_creative_share_lot)));
                    return;
                }catch(RuntimeException unavailable){if(file!=null)file.delete();success=false;}
            }
            Toast.makeText(activity,Language.msg(success
                    ?com.eurobuddha.minimacore.i18n.R.string.l10n_creative_saved_photos
                    :share?com.eurobuddha.minimacore.i18n.R.string.l10n_creative_share_image_failed
                    :com.eurobuddha.minimacore.i18n.R.string.l10n_creative_save_image_failed),Toast.LENGTH_SHORT).show();
        }
    }

