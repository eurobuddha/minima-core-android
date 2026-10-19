"""Keep Salon's bounded sections, using AndroidX's native nested-scroll handoff."""
from pathlib import Path

def patch(s):
    s=s.replace('import android.widget.ScrollView;', 'import androidx.core.widget.NestedScrollView;')
    s=s.replace('private ScrollView scroll;', 'private NestedScrollView scroll;')
    s=s.replace('scroll = new ScrollView(this);', 'scroll = new NestedScrollView(this);')
    s=s.replace('private class BoundedScroll extends ScrollView {', 'private class BoundedScroll extends NestedScrollView {')
    # Both old overrides captured DOWN until UP, preventing edge handoff.
    for name in ('BoundedRecycler', 'BoundedScroll'):
        start=s.index('    private class '+name+' ')
        end=s.index('\n    }',start)
        method=s.find('        @Override public boolean dispatchTouchEvent(',start,end)
        if method!=-1:
            s=s[:method]+s[end:]
    s=s.replace('    /** A ScrollView that grows with its content up to a maximum height, then scrolls\n'
                '     *  internally — for heterogeneous sections (Gallery grid+carousel+list, Holdings\n'
                "     *  rows) that don't map cleanly onto an adapter. Same gesture-claim as\n"
                "     *  {@link BoundedRecycler} so the outer ScrollView doesn't steal the drag. */",
                '    /** Bounded gallery/holdings window. AndroidX passes movement beyond either\n'
                '     *  edge to the page in the same gesture, including RecyclerView posts. */')
    return s

def adapt(root):
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java'
    p.write_text(patch(p.read_text()))
