"""Reuse NFT Wallet's rule: live coin presence/absence cannot settle an uncertain send."""
from pathlib import Path

def repair(s):
    a=s.index('    /**\n     * Rows left UNKNOWN')
    b=s.index('    /** The values of',a)
    return s[:a]+"""    /**
     * Compatibility entrypoint for coin refreshes. A coin list (including a partial one)
     * cannot prove whether a timed-out transaction was submitted. Preserve its recorded
     * outcome across elapsed time and restarts; settlement requires transaction evidence.
     */
    public boolean reconcileUnknown(java.util.Set<String> liveCoinIds, long staleMs) {
        return false;
    }

"""+s[b:]

def adapt(root):
    p=root/'utxo/src/main/java/com/eurobuddha/utxo/HistoryDb.java';p.write_text(repair(p.read_text()))
    p=root/'utxo/build.gradle';s=p.read_text().replace('    compileOptions {','    testOptions { unitTests.includeAndroidResources = true; unitTests.returnDefaultValues = true }\n    compileOptions {').replace("    testImplementation 'junit:junit:4.13.2'","    testImplementation 'org.robolectric:robolectric:4.14.1'\n    testImplementation 'junit:junit:4.13.2'");p.write_text(s)
