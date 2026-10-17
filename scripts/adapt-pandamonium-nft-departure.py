"""Confirm individual StateNFT actions from intended outputs and guard unfinished mints."""
from pathlib import Path
import shutil

def adapt(root):
 project=Path(__file__).resolve().parents[1]
 folder=root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
 p=folder/'StateNftActions.java';s=p.read_text()
 def change(old,new,n=1):
  nonlocal s
  assert s.count(old)==n,('NFT departure source drift',old,s.count(old),n)
  s=s.replace(old,new)
 change('never trust txnpost status — confirm by watching the input coin leave the UTXO set.', 'never trust txnpost status — confirm the intended output, amount and preserved state on-chain.')
 for name,build,dest,success in [
  ('Transfer','l10n_nft_actions_building','to','Language.msg(l10n_nft_actions_transferred, displayName, to)'),
  ('Bury','l10n_nft_actions_building_bury','StateNft.GRAVEYARD','Language.msg(l10n_nft_actions_buried_name, displayName)')]:
  old='Language.msg('+build+'));\n        CmdChain.run'
  change(old,'Language.msg('+build+'));\n        final JSONObject intent = departureIntent(act, tokenid, coin, progress);\n        if (intent == null) return;\n        CmdChain.run')
  change('watchDeparture(act, tokenid, coinid, progress,\n                        '+success+');','confirmDeparture(act, tokenid, intent, '+dest+', last, progress,\n                        '+success+');')
 start=s.index('    /**\n     * txnpost status:true is NOT proof');end=s.index('    // ===================== plumbing',start)
 s=s[:start]+(project/'scripts/pandamonium-sources/NFTwallet/departure-flow.inc').read_text()+s[end:]
 start=s.index('    private static void gatherAndTransfer(');end=s.index('        act.node().cmd(',start)
 s=s[:end]+'''        if (!LocalStore.canDepart(act, tokenid)) {
            setMessage(progress, Language.msg(l10n_nft_actions_mint_unfinished));
            makeDismissable(progress); return;
        }
'''+s[end:]
 p.write_text(s)
 for name in ['DepartureEvidenceTest','DepartureFlowTest']:
  p=project/'scripts/pandamonium-tests/NFTwallet/src/test/java/com/eurobuddha/nftwallet'/(name+'.java')
  if p.exists():shutil.copy2(p,root/'NFTwallet/src/test/java/com/eurobuddha/nftwallet'/p.name)
