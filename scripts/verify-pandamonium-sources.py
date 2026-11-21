#!/usr/bin/env python3
"""Regenerate the miniMall and NFT Wallet sources that the import copies from scripts/pandamonium-sources and diff them
against integrated/ (point release 2026-11-21, item 4): a fix made in integrated/ without its template, or a template
changed without integrated/, fails here. Runs before every build (app/build.gradle preBuild)."""
import difflib, importlib.util, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
T = ROOT/'scripts/pandamonium-sources'
I = ROOT/'integrated'
INBOX = 'merchinbox/src/main/java/com/eurobuddha/merchinbox/'

def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), ROOT/'scripts'/(name + '.py'))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m

def main():
    plain = load('adapt-pandamonium-inbox-plain-delivery')
    security = load('adapt-pandamonium-mall-security')
    checks = [  # (template, integrated file, what the import does to the template)
        ('merchinbox/DeliveryJournal.java', INBOX + 'DeliveryJournal.java', plain.journal_edits),
        ('merchinbox/DeliveryFlow.java', INBOX + 'DeliveryFlow.java', None),
        ('merchinbox/PlainDeliveryJournal.java', INBOX + 'PlainDeliveryJournal.java', None),
        ('merchinbox/PlainDeliveryFlow.java', INBOX + 'PlainDeliveryFlow.java', None),
        ('merchinbox/ReplyDrafts.java', INBOX + 'ReplyDrafts.java', None),
        ('merchshop/PaymentFlow.java', 'merchshop/src/main/java/com/eurobuddha/merchshop/PaymentFlow.java', None),
        ('merchshop/PaymentJournal.java', 'merchshop/src/main/java/com/eurobuddha/merchshop/PaymentJournal.java', None),
        ('NFTwallet/OrderPaymentEvidence.java', 'NFTwallet/src/main/java/com/eurobuddha/nftwallet/OrderPaymentEvidence.java', None),
        ('merchstudio/ShopExport.java', 'merchstudio/src/main/java/com/eurobuddha/merchstudio/ShopExport.java', None),
        ('merchstudio/StudioData.java', 'merchstudio/src/main/java/com/eurobuddha/merchstudio/StudioData.java', None),
    ] + [('merchinbox/' + n, INBOX + n, None) for n in security.FILES]
    bad = 0
    for template, target, edit in checks:
        made = (T/template).read_text(encoding='utf-8')
        if edit: made = edit(made)
        have = (I/target).read_text(encoding='utf-8')
        if made != have:
            bad += 1
            print('MISMATCH ' + template + ' -> integrated/' + target)
            sys.stdout.writelines(list(difflib.unified_diff(made.splitlines(True), have.splitlines(True), 'regenerated', 'integrated', n=1))[:40])
    print(('verify-pandamonium-sources: %d of %d differ' % (bad, len(checks))) if bad else 'verify-pandamonium-sources: %d sources match' % len(checks))
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main())
