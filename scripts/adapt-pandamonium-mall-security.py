"""The miniMall Inbox's delivery rules, ledger, listing check and refusal messages, as reviewed (security reviews
2026-11-20 and the point release of 2026-11-21): copied from scripts/pandamonium-sources/merchinbox after the other
Inbox steps. scripts/verify-pandamonium-sources.py checks these templates against integrated/."""
from pathlib import Path

FILES = ['DeliveryLedger.java', 'DeliveryRules.java', 'Listing.java', 'InboxMessages.java']

def adapt(root):
    source=Path(__file__).with_name('pandamonium-sources')/'merchinbox'
    folder=root/'merchinbox/src/main/java/com/eurobuddha/merchinbox'
    for name in FILES: (folder/name).write_text((source/name).read_text())
