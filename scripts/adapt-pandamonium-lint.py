"""Retain standalone UI compatibility fixes and clean injected launch indentation."""
from pathlib import Path
import re

def adapt(root):
    def change(relative, old, new, count=1):
        p=root/relative;s=p.read_text()
        assert s.count(old)==count,(relative,old,s.count(old))
        p.write_text(s.replace(old,new))
    # Donors: apks/mail MainActivity, apks/NFTwallet ReceiveView, apks/merchstudio MainActivity.
    for method,count in [('isDrawerOpen',1),('closeDrawer',2),('openDrawer',1)]:
        change('mail/src/main/java/com/eurobuddha/mail/MainActivity.java',
            'drawer.'+method+'(Gravity.START)',
            'drawer.'+method+'(androidx.core.view.GravityCompat.START)',count)
    change('NFTwallet/src/main/java/com/eurobuddha/nftwallet/ReceiveView.java',
        'android.text.Layout.BREAK_STRATEGY_SIMPLE','android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE')
    change('merchstudio/src/main/java/com/eurobuddha/merchstudio/MainActivity.java',
        'ImageView iv = new ImageView(this) {',
        'ImageView iv = new androidx.appcompat.widget.AppCompatImageView(this) {')
    # An earlier package-name adapter inserted these fallback lines with fixed indentation.
    for app in ['terminalide','pandapools']:
        p=root/app/'src/main/java/com/eurobuddha'/app/'MainActivity.java';s=p.read_text()
        pattern=r'(?m)^([ \t]*)(Intent (?:launch|i) = getPackageManager\(\).getLaunchIntentForPackage\([^\n]+\);)\n((?:[ \t]*if \((?:launch|i) == null\)[^\n]+\n)+)'
        def indent(m):
            return m[1]+m[2]+'\n'+''.join(m[1]+line.strip()+'\n' for line in m[3].splitlines())
        s,n=re.subn(pattern,indent,s);assert n==1,(app,n);p.write_text(s)
