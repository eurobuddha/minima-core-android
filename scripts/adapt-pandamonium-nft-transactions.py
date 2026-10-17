"""Reuse UTXO's single-burn and delayed-distribution fixes in the NFT wallet."""

def adapt(root):
    nft = root/'NFTwallet/src/main/java/com/eurobuddha/nftwallet'
    utxo = root/'utxo/src/main/java/com/eurobuddha/utxo'
    def change(path, old, new):
        text = path.read_text()
        assert text.count(old) == 1, ('NFT transaction source drift', path, old)
        path.write_text(text.replace(old, new))

    # SendView already reserves the requested burn in the input/output difference.
    # Reuse the sibling's command and explanation, without copying its timeout policy.
    donor = (utxo/'TxnBuilder.java').read_text()
    start = donor.index('        // BURN:')
    end = donor.index('                new NodeApi.Cb() {', start)
    target = (nft/'TxnBuilder.java').read_text()
    old_start = target.index('        // Burn is only valid')
    old_end = target.index('                new NodeApi.Cb() {', old_start)
    change(nft/'TxnBuilder.java', target[old_start:old_end], donor[start:end])
    change(nft/'TxnBuilder.java', '// Minima-only burn (txnpostburn); null/"0" = none',
           '// Minima-only burn, already reserved by the caller in the input/output difference')

    # A resumed job may already have a confirmed change coin after its old deadline.
    # Keep NFT's confirmed/sendable gates and all its persistence behavior intact.
    donor = (utxo/'DistributeManager.java').read_text()
    start = donor.index('        Coin change = findChangeCoin();')
    end = donor.index('        // Change coin confirmed:', start)
    target = (nft/'DistributeManager.java').read_text()
    old_start = target.index('        // Give up on a stuck batch')
    old_end = target.index('        // Change coin confirmed:', old_start)
    change(nft/'DistributeManager.java', target[old_start:old_end], donor[start:end])
