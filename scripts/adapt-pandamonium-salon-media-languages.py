"""Retain Salon gallery/player views and guard delayed playback against newer choices."""
from pathlib import Path
import json,re

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    def msg(key,args=''):
        return 'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',key).replace('.','_').lower()+args+')'
    def change(old,new):
        nonlocal s
        assert old in s,('Salon media drift',old);s=s.replace(old,new)
    a=s.index('    private void promptMediaTitle(');b=s.index('    /** A real avatar',a);part=s[a:b]
    def replace(old,new):
        nonlocal part
        assert old in part,('Salon media section drift',old);part=part.replace(old,new)
    replace('    private void promptMediaTitle(String kind, String url) {','    private void promptMediaTitle(String kind, String url) {\n        final java.util.function.BooleanSupplier current = editorDraftCurrent("gallery");')
    replace('String label = kind.equals("audio")','CharSequence label = kind.equals("audio")')
    replace('new android.app.AlertDialog.Builder(this).setTitle("Add to gallery")','new com.eurobuddha.minimacore.i18n.LanguageDialog(this).setTitle("Add to gallery")')
    replace('(d, w) -> addGalleryItem(kind, url, text(t))','(d, w) -> { if (current.getAsBoolean()) addGalleryItem(kind, url, text(t)); }')
    replace('(d, w) -> addGalleryItem(kind, url, "")','(d, w) -> { if (current.getAsBoolean()) addGalleryItem(kind, url, ""); }')
    replace('String kindLabel =','CharSequence kindLabel =')
    replace('final String title = m.optString("caption", "").isEmpty()', 'final CharSequence title = m.optString("caption", "").isEmpty()')
    replace('new LinearLayout.LayoutParams(0, dp(46), 1)', 'new LinearLayout.LayoutParams(0, -2, 1)')
    for prefix,variable,key in [('Photos','photos','photos'),('Video','videos','videos'),('Music','tracks','tracks')]:
        replace('"'+prefix+' · " + '+variable+'.size()',msg('salonMedia.'+key,', '+variable+'.size()'))
    replace('private TextView subLabel(String s)', 'private TextView subLabel(CharSequence s)')
    replace('Design.text(this, s.toUpperCase(), 9f','Design.text(this, Language.upper(s), 9f')
    for method in ['playTrack','playTrackSource']:
        replace('private void '+method+'(String '+('url' if method=='playTrack' else 'source')+', String title)', 'private void '+method+'(String '+('url' if method=='playTrack' else 'source')+', CharSequence title)')
    replace('miniTitle.setText(title)', 'Language.setText(miniTitle, title)')
    replace('miniTime.setText("decrypting…")', 'Language.setText(miniTime, "decrypting…")')
    replace('"Couldn\'t load video: " + e.getMessage()',msg('salonMedia.videoFailed',', e.getMessage()'))
    replace('"Couldn\'t load track: " + e.getMessage()',msg('salonMedia.trackFailed',', e.getMessage()'))
    replace('"Couldn\'t play: " + e.getMessage()',msg('salonMedia.playFailed',', e.getMessage()'))
    labels=json.loads((Path(__file__).parent/'pandamonium-sources/salon/media-language-labels.json').read_text())
    for literal,key in labels.items():replace(json.dumps(literal,ensure_ascii=False),msg(key))
    # Reuse openProfile's request identity; every stop or new source invalidates old resolution.
    replace('    private void playTrack(String url, CharSequence title) {','    private long audioRequest;\n\n    private void playTrack(String url, CharSequence title) {')
    replace('''        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            stopAudio(); Language.setText(miniTitle, title);''','''        if (isFinishing() || isDestroyed()) return;
        stopAudio();
        final long request = audioRequest;
        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            Language.setText(miniTitle, title);''')
    replace('runOnUiThread(() -> playTrackSource(f.getAbsolutePath(), title))','runOnUiThread(() -> { if (request == audioRequest && !isFinishing() && !isDestroyed()) playTrackSource(f.getAbsolutePath(), title); })')
    replace('runOnUiThread(() -> { toast('+msg('salonMedia.trackFailed',', e.getMessage()')+'); stopAudio(); })','runOnUiThread(() -> { if (request != audioRequest || isFinishing() || isDestroyed()) return; toast('+msg('salonMedia.trackFailed',', e.getMessage()')+'); stopAudio(); })')
    replace('''    private void playTrackSource(String source, CharSequence title) {
        stopAudio();''','''    private void playTrackSource(String source, CharSequence title) {
        if (isFinishing() || isDestroyed()) return;
        stopAudio();''')
    replace('mp.setOnPreparedListener(x -> { mp.start();','mp.setOnPreparedListener(x -> { if (audio != mp || isFinishing() || isDestroyed()) return; mp.start();')
    replace('mp.setOnCompletionListener(x -> { miniBar.setProgress(1000);','mp.setOnCompletionListener(x -> { if (audio != mp || isFinishing() || isDestroyed()) return; miniBar.setProgress(1000);')
    replace('mp.setOnErrorListener((m, w, e) -> { toast(', 'mp.setOnErrorListener((m, w, e) -> { if (audio != mp || isFinishing() || isDestroyed()) return true; toast(')
    replace('    private void stopAudio() {','    private void stopAudio() {\n        audioRequest++;\n        miniSeeking = false;')
    # Old video resolution must not reopen a dismissed/replaced player.
    replace('    private void openVideo(String url) {', '    private long videoRequest;\n    private Dialog videoDialog;\n\n    private void openVideo(String url) {')
    replace('''    private void openVideo(String url) {
        if (url == null || url.isEmpty()) return;''','''    private void openVideo(String url) {
        if (url == null || url.isEmpty() || isFinishing() || isDestroyed()) return;
        if (videoDialog != null) videoDialog.dismiss();
        final long request = ++videoRequest;''')
    replace('runOnUiThread(() -> playVideoUri(Uri.fromFile(f)))','runOnUiThread(() -> { if (request == videoRequest && !isFinishing() && !isDestroyed()) playVideoUri(Uri.fromFile(f)); })')
    replace('runOnUiThread(() -> toast('+msg('salonMedia.videoFailed',', e.getMessage()')+'))','runOnUiThread(() -> { if (request == videoRequest && !isFinishing() && !isDestroyed()) toast('+msg('salonMedia.videoFailed',', e.getMessage()')+'); })')
    replace('''    private void playVideoUri(Uri uri) {
        Dialog d''','''    private void playVideoUri(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        if (videoDialog != null) videoDialog.dismiss();
        Dialog d''')
    replace('d.setContentView(fl); d.show();','videoDialog = d; d.setContentView(fl); d.show();')
    replace('vv.setOnPreparedListener(mp -> { mp.setLooping(false);','vv.setOnPreparedListener(mp -> { if (videoDialog != d || !d.isShowing() || isFinishing() || isDestroyed()) return; mp.setLooping(false);')
    replace('d.setOnDismissListener(x -> vv.stopPlayback());','d.setOnDismissListener(x -> { vv.stopPlayback(); if (videoDialog == d) { videoDialog = null; videoRequest++; } });')
    replace('if (videoDialog != null) videoDialog.dismiss();', 'dismissVideo();')
    replace('    private void playVideoUri(Uri uri) {', '''    private void dismissVideo() {
        // onDismiss is queued: detach the old owner before choosing the next request.
        Dialog previous = videoDialog; videoDialog = null;
        if (previous != null) previous.dismiss();
    }

    private void playVideoUri(Uri uri) {''')
    # Accessible symbolic controls use retained descriptions without changing symbols.
    replace('root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));','Language.description(close, '+msg('language.close')+');\n        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));')
    replace('final TextView prev = navArrow("‹"), next = navArrow("›");','final TextView prev = navArrow("‹"), next = navArrow("›");\n        Language.description(prev, '+msg('salonMedia.previous')+');\n        Language.description(next, '+msg('common.next')+');')
    replace('counter.setText((idx[0] + 1) + " / " + items.size());','Language.setText(counter, '+msg('salonMedia.position',', idx[0] + 1, items.size()')+');')
    replace('miniPlayer.addView(miniBar, new LinearLayout.LayoutParams(-1, -2));','Language.description(miniBar, '+msg('salonMedia.seek')+');\n        miniPlayer.addView(miniBar, new LinearLayout.LayoutParams(-1, -2));')
    for symbol,key in [('⏪','backTen'),('⏩','forwardTen'),('✕','stop')]:
        replace('miniIcon("'+symbol+'",', 'miniIcon("'+symbol+'", '+msg('salonMedia.'+key)+',')
    replace('miniIcon("❚❚",', 'miniIcon("❚❚", '+msg('logs.pause')+',')
    replace('private TextView miniIcon(String s, Runnable click)', 'private TextView miniIcon(String s, CharSequence description, Runnable click)')
    replace('t.setOnClickListener(v -> click.run()); return t;', 'Language.description(t, description); t.setMinWidth(dp(48)); t.setMinHeight(dp(48));\n        t.setOnClickListener(v -> click.run()); return t;')
    replace('miniPlay.setText(playing ? "❚❚" : "▶");', 'miniPlay.setText(playing ? "❚❚" : "▶"); Language.description(miniPlay, playing ? '+msg('logs.pause')+' : '+msg('salonMedia.play')+');')
    s=s[:a]+part+s[b:]
    change('        stopAudio();\n        proofRequests.close();','        stopAudio();\n        videoRequest++;\n        dismissVideo();\n        proofRequests.close();')
    p.write_text(s)
