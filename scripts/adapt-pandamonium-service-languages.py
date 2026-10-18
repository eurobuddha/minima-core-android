"""Adapt Parlons explicit language listeners to existing service notification refreshes.
Run after all individual app adapters; retain the financial and scheduling methods verbatim.
"""
from pathlib import Path

SERVICES = {
    'atomix': ('atomix', 'SwapService'),
    'futurecash-next': ('futurecashnext', 'GuardianService'),
    'casino': ('casino', 'CasinoService'),
    'NFTwallet': ('nftwallet', 'MintService'),
    'pandapools': ('pandapools', 'PoolKeepAliveService'),
    'pandadex': ('pandadex', 'DexKeepAliveService'),
}

def adapt(root):
    for app, (package, name) in SERVICES.items():
        p = root / app / f'src/main/java/com/eurobuddha/{package}/{name}.java'
        s = p.read_text()
        def change(old, new):
            nonlocal s
            assert s.count(old) == 1, (name, 'service language source drift', old)
            s = s.replace(old, new)
        if 'import com.eurobuddha.minimacore.i18n.Language;' not in s:
            change(f'package com.eurobuddha.{package};', f'package com.eurobuddha.{package};\nimport com.eurobuddha.minimacore.i18n.Language;')
        change(f'public class {name} extends Service {{', f'public class {name} extends Service {{\n\n    private final Runnable languageChanged = this::refreshLanguageNotification;')
        # All six use this successful foreground-promotion gate. No listener for a refused start.
        gate = '        if (!startForegroundCompat()) { stopSelf(); return; }'
        change(gate, gate + '\n        Language.addListener(languageChanged);')
        change('    @Override public void onDestroy() {', '    @Override public void onDestroy() {\n        Language.removeListener(languageChanged);')
        start = s.index('    @Override public void onConfigurationChanged(')
        end = s.index('    @Override public void onDestroy()', start)
        old = s[start:end]
        prefix = '    @Override public void onConfigurationChanged(android.content.res.Configuration config) {\n        super.onConfigurationChanged(config);\n'
        assert old.startswith(prefix) and old.endswith('    }\n\n'), name
        body = old[len(prefix):-len('    }\n\n')].replace('        Language.configurationChanged(this);\n', '')
        change(old, prefix + '        Language.configurationChanged(this);\n        refreshLanguageNotification();\n    }\n\n    private void refreshLanguageNotification() {\n' + body + '    }\n\n')
        if app == 'NFTwallet':
            change('        if (foregroundActive) { fgText = ""; updateFg(stateText()); }', '''        if (foregroundActive) {
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) manager.notify(FG_ID, buildFg(fgText.isEmpty() ? "Working…" : fgText));
        }''')
        p.write_text(s)
