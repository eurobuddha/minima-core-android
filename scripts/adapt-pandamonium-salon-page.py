"""Publish the existing Salon page with offline labels and an explicit language selector."""
from pathlib import Path
import json


def adapt(root):
    project = Path(__file__).resolve().parents[1]
    source = project / 'scripts/pandamonium-sources/salon'
    catalog = json.loads((project / 'localization/catalog.json').read_text())
    keys = ['language.title', 'salonSocial.about', 'creative.links', 'nftWallet.gallery',
            'salonSocial.posts', 'salonPage.loading', 'salonPage.unavailable']
    messages = {code: {key: catalog[key][code] for key in keys} for code in ['en', 'zh-Hans', 'ru', 'uk']}
    html = (source / 'salon-page.html').read_text()
    html = html.replace('__SALON_MESSAGES__', json.dumps(messages, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c'))
    html = html.replace('__SALON_I18N__', (source / 'salon-page-i18n.js').read_text())
    p = root / 'salon/src/main/java/com/eurobuddha/salon/MainActivity.java'
    s = p.read_text()
    assert s.count('SALON_HTML.getBytes("UTF-8")') == 2
    s = s.replace('SALON_HTML.getBytes("UTF-8")', 'salonHtml().getBytes("UTF-8")')
    start = s.index('    private static final String SALON_HTML =')
    # The donor's final member is the hosted page. Replace only that constant.
    assert s[start:].rstrip().endswith('</html>";\n}')
    s = s[:start] + '''    private static String salonHtml() {
        return SALON_HTML.replace("__SALON_LANGUAGE__", Language.code());
    }

    private static final String SALON_HTML = ''' + json.dumps(html, ensure_ascii=False) + ';\n}\n'
    p.write_text(s)
