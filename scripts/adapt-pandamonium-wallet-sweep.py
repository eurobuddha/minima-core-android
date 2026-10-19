"""Prepare Sweep restoration using the standalone planner and the wallet's existing whole-coin builder."""
from pathlib import Path

def adapt(root):
    base=root/'wallet/src/main/java/com/eurobuddha/wallet'
    source=Path(__file__).with_name('pandamonium-sources')/'wallet'
    for name in ('SweepPlanner.java','SweepFlow.java','SweepPending.java'):
        (base/name).write_text((source/name).read_text())
    settings=base/'SettingsView.java';text=settings.read_text()
    marker='        // --- Seed backup '
    if 'new SweepFlow(act)' not in text:
        pos=text.index(marker)
        text=text[:pos]+'''        container.addView(sectionTitle(Language.msg(l10n_sweep_title)));
        container.addView(outlineButton(Language.msg(l10n_sweep_settings),v->{if(currentSession())new SweepFlow(act).start();}));
        container.addView(hint(Language.msg(l10n_sweep_help)));

'''+text[pos:]
        settings.write_text(text)
    nft=base/'NftView.java';text=nft.read_text()
    if 'static java.util.Set<String> pendingCoinIds()' not in text:
        text=text.replace('private static final java.util.HashMap<String, Long> PENDING = new java.util.HashMap<>();',
            'private static final java.util.Map<String, Long> PENDING = new java.util.concurrent.ConcurrentHashMap<>();')
        pos=text.index('    private static boolean isPending(')
        text=text[:pos]+'''    static java.util.Set<String> pendingCoinIds(){
        java.util.Set<String> result=new java.util.HashSet<>();
        for(String id:PENDING.keySet())result.add(id.toLowerCase(java.util.Locale.ROOT));
        return result;
    }
    static long markPending(String id){
        return PENDING.compute(id,(key,previous)->Math.max(System.currentTimeMillis(),previous==null?0:previous+1));
    }
    static void clearSweepPending(String id,long stamp){PENDING.remove(id,stamp);}
'''+text[pos:]
        nft.write_text(text)
    p=base/'comms/NodeLink.java';s=p.read_text()
    if 'interface SizeAwareCb' not in s:
        marker='    public void publish(final TxnFactory.BuiltTxn zBuilt, final NodeApi.Cb zCb) {'
        helper=r"""    /** Only a structured, correlated pre-mining refusal can authorize sweep splitting. */
    public interface SizeAwareCb extends NodeApi.Cb { void onSizeRefusal(JSONObject reply); }
    public static boolean isSizeRefusal(JSONObject reply,String id) {
        if(reply==null||id==null||!Boolean.FALSE.equals(reply.opt("status"))
            ||!"txnpost".equals(reply.opt("command"))||!Boolean.FALSE.equals(reply.opt("pending"))
            ||reply.has("response")||reply.optJSONObject("params")==null
            ||!id.equals(reply.optJSONObject("params").opt("id")))return false;
        Object error=reply.opt("error");if(!(error instanceof String))return false;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("TxPoW size too large\\.\\. ([1-9][0-9]{0,18})/([1-9][0-9]{0,18})").matcher((String)error);
        if(!match.matches())return false;
        java.math.BigInteger size=new java.math.BigInteger(match.group(1)),max=new java.math.BigInteger(match.group(2));
        return max.compareTo(java.math.BigInteger.valueOf(65536))>=0&&size.compareTo(max)>0;
    }

"""
        assert marker in s;s=s.replace(marker,helper+marker)
        s=s.replace('        // Step 1 — txnimport (signatures-only witness).',
            '        final java.util.concurrent.atomic.AtomicBoolean imported=new java.util.concurrent.atomic.AtomicBoolean(), basics=new java.util.concurrent.atomic.AtomicBoolean(), posted=new java.util.concurrent.atomic.AtomicBoolean();\n        // Step 1 — txnimport (signatures-only witness).')
        for response,guard in [('importResp','imported'),('basicsResp','basics'),('postResp','posted')]:
            marker='public void onResult(JSONObject '+response+') {'
            assert marker in s;s=s.replace(marker,marker+'\n'+(' '*{'imported':16,'basics':24,'posted':32}[guard])+'if(!'+guard+'.compareAndSet(false,true))return;')
        marker='                                if (!postResp.optBoolean("status", false)) {'
        assert marker in s;s=s.replace(marker,marker+'\n                                    if(zCb instanceof SizeAwareCb && isSizeRefusal(postResp,zBuilt.getID())){((SizeAwareCb)zCb).onSizeRefusal(postResp);return;}')
        marker='if (zCb != null) zCb.onError(message);'
        assert s.count(marker)==3
        for guard in ['posted','basics','imported']:
            s=s.replace(marker,'if ('+guard+'.compareAndSet(false,true) && zCb != null) zCb.onError(message);',1)
        p.write_text(s)
    p=base/'TxnFactory.java';s=p.read_text()
    if '    public BuiltTxn buildWholeTransfer(' in s:
        return
    start=s.index('    public BuiltTxn buildNftTransfer(')
    position=s.index('        MiniData recipient = parseAddress(zRecipient);',start)
    s=s[:position]+'''        return buildWholeTransfer(zCoin,zRecipient,zID);
    }

    /** Sweep can preserve state on native Minima too. Reuse the exact NFT whole-coin builder. */
    public BuiltTxn buildWholeTransfer(InputCoin zCoin, String zRecipient, String zID) {
'''+s[position:]
    p.write_text(s)
