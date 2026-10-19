"""Apply Salon publication feedback after the existing publishing / Keets adapters."""
from pathlib import Path
import shutil

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon/publication'
    target=root/'salon/src/main/java/com/eurobuddha/salon'
    shutil.copy2(source/'SalonPublication.java',target/'SalonPublication.java')
    p=target/'SalonRegistry.java';r=p.read_text()
    r=r.replace('interface Announced { void done(boolean ok, String message); }','interface Announced { void done(boolean ok, String message); default void rejected(String message) { done(false,message); } }')
    r=r.replace('else cb.done(false, json.optString("error", "publish failed"));','else if (Boolean.FALSE.equals(json.opt("status"))) cb.rejected(json.optString("error", "publish failed"));\n                else cb.done(false, "Publication response was incomplete.");')
    p.write_text(r)
    p=target/'MainActivity.java';s=p.read_text()
    if 'private SalonPublication publicationCheck;' in s:return
    ui=(source/'PublicationUi.java.inc').read_text()
    def msg(key):return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_progress_'+key+')'
    ui=ui.replace('SET_STATUS_LOCAL','Language.setText(status,message)').replace('SET_STATUS','Language.setText(profStatus,message)').replace('SET_LABEL','Language.setText(publishButton,Language.upper(label))')
    for k in ['PREPARING','CONFIRMING','CHECK','VIEW','SAVE','NODE']:ui=ui.replace('LABEL_'+k,msg(k.lower()))
    ui+='    private CharSequence publishMessage(SalonPublication.Stage stage) {\n        switch(stage) {\n'
    for k in ['CHECKING','ANNOUNCING','WAITING','CONFIRMED','UNKNOWN','FAILED','REJECTED']:ui+='            case '+k+': return '+msg(k.lower())+';\n'
    ui+='            default: throw new IllegalArgumentException();\n        }\n    }\n'
    a=s.index('    private void publishSalon(TextView status) {');b=s.index('    private static final class SpareBurn',a)
    s=s[:a]+ui+'\n'+s[b:]
    s=s.replace('        profStatus = Design.note(this, ""); body.addView(profStatus, lp(0, 4, 0, 0));','        if (!publishOwner.equals(SalonStore.get(this,"tokenid"))) publishStage=null;\n        profStatus = Design.note(this, ""); body.addView(profStatus, lp(0, 4, 0, 0));')
    old='body.addView(btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_editor_publish), true, () -> {'
    assert old in s;s=s.replace(old,'body.addView(publishAction(() -> {')
    needle='        }), lp(0, 8, 0, 0));\n    }\n\n    /** A modal edit'
    assert needle in s;s=s.replace(needle,'        }), lp(0, 8, 0, 0));\n        resumePublication();\n    }\n\n    /** A modal edit')
    s=s.replace('    private void hostProfile(TextView status, Runnable done) {','    private void hostProfile(TextView status, Runnable done) {\n        if(pendingPublication()) { publishSalon(status); return; }')
    s=s.replace('        profilePublish = op;','        profilePublish = op;\n        publishPreparing=true;updatePublishButton();')
    s=s.replace('        profilePublish = null;','        profilePublish = null;\n        publishPreparing=false;updatePublishButton();')
    s=s.replace('        KeetsLink.release(node);','        if(publicationCheck!=null) publicationCheck.stop();\n        KeetsLink.release(node);')
    # Return to an editor with new edits as a fresh editing session, not a stale View button.
    s=s.replace('        screen = s; render();','        if(s==Screen.EDIT && screen!=Screen.EDIT) publishStage=null;\n        screen = s; render();')
    s=s.replace('        if (!nodeUp) startPairingRetry();','        if(nodeUp && screen==Screen.EDIT) resumePublication();\n        if (!nodeUp) startPairingRetry();')
    s=zoom(s)
    shutil.copy2(source/'ZoomImageView.java',target/'ZoomImageView.java')
    p.write_text(s)

def zoom(s):
    s=s.replace('ImageView iv = new ImageView(this); iv.setBackgroundColor(0xFF000000); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);','ZoomImageView iv = new ZoomImageView(this); iv.setBackgroundColor(0xFF000000);')
    s=s.replace('ImageLoader.loadFull(this, url, iv); iv.setOnClickListener(v -> d.dismiss());','ImageLoader.loadFull(this, url, iv); iv.setTapListener(d::dismiss);')
    s=s.replace('final ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.FIT_CENTER);','final ZoomImageView img = new ZoomImageView(this);')
    a=s.index('        final android.view.GestureDetector gd =',s.index('    private void openCarousel('))
    b=s.index('        bind[0].run();',s.index('img.setClickable(true); img.setOnTouchListener',a))
    s=s[:a]+"""        img.setSwipeListener(nextImage -> {
            int target=idx[0]+(nextImage?1:-1);
            if(target>=0 && target<items.size()) { idx[0]=target;bind[0].run(); }
        });
"""+s[b:]
    return s
