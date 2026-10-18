"""Generate core chat display diagnostics with the proven WalletMessages builder."""
from pathlib import Path
import importlib.util
import json
import sys

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    'chat_message_builder', root / 'scripts/adapt-pandamonium-wallet-error-languages.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
source = builder.build_source(
    json.loads((root / 'localization/chat-errors.json').read_text()),
    json.loads((root / 'localization/catalog.json').read_text()))
source = source.replace('com.eurobuddha.wallet', 'com.eurobuddha.minimacore.chat')
source = source.replace('WalletMessages', 'ChatMessages')
source = source.replace('l10n_wallet_operation_failed', 'l10n_chat_error_operation_failed')
source = source.replace('Explicit wallet failures only.', 'Explicit chat failures only.')
source = source.replace('adapt-pandamonium-wallet-error-languages.py from localization/wallet-errors.json',
                        'tools/localization/generate.cjs from localization/chat-errors.json')
sys.stdout.write(source)
