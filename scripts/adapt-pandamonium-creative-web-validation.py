"""Reuse the host wallet verifier and retain each callback on its owning view."""
from pathlib import Path

def adapt(root):
    source=(Path(__file__).parent/'pandamonium-sources/CreativeWebValidate.java.inc').read_text()
    for app,pkg in [('atelier','com.eurobuddha.statenft'),('salon','com.eurobuddha.salon')]:
        base=root/app/'src/main/java'/pkg.replace('.','/')
        (base/'WebValidate.java').write_text(source.replace('__PACKAGE__',pkg))
        p=base/'MainActivity.java';s=p.read_text()
        def change(old,new):
            nonlocal s
            assert old in s,(app,'web validation caller drift',old);s=s.replace(old,new)
        change('WebValidate.status(tokenid)', 'WebValidate.status(tokenid, webvalidate)')
        change('WebValidate.ensure(this, tokenid, webvalidate, paint)', 'WebValidate.ensure(this, f, tokenid, webvalidate, paint)')
        if app=='atelier':
            change('WebValidate.status(m.tokenid)', 'WebValidate.status(m.tokenid, m.webvalidate)')
            change('WebValidate.ensure(this, m.tokenid, m.webvalidate, paint)', 'WebValidate.ensure(this, hb, m.tokenid, m.webvalidate, paint)')
        else:
            change('WebValidate.status(boundIdentity)', 'WebValidate.status(boundIdentity, wv)')
            change('WebValidate.ensure(this, boundIdentity, wv,', 'WebValidate.ensure(this, shieldSlot, boundIdentity, wv,')
        p.write_text(s)
        if app=='atelier':
            p=base/'ViewerScreen.java';s=p.read_text()
            assert 'WebValidate.status(meta.tokenid)' in s
            s=s.replace('WebValidate.status(meta.tokenid)', 'WebValidate.status(meta.tokenid, meta.webvalidate)')
            assert 'WebValidate.ensure(act, meta.tokenid, meta.webvalidate, paintShield)' in s
            s=s.replace('WebValidate.ensure(act, meta.tokenid, meta.webvalidate, paintShield)', 'WebValidate.ensure(act, shieldSlot, meta.tokenid, meta.webvalidate, paintShield)')
            p.write_text(s)
