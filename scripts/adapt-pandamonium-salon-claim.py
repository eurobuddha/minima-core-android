"""Reuse owned publication lifetimes and checked saves for Salon account setup."""
from pathlib import Path

def adapt(root):
    source=Path(__file__).parent/'pandamonium-sources/salon'
    p=root/'salon/src/main/java/com/eurobuddha/salon/MainActivity.java';s=p.read_text()
    snippet=(source/'ClaimIdentity.java.inc').read_text()
    keys={'CHANGED':'salon_publish_changed','STORAGE':'salon_publish_storage','BUSY':'salon_setup_claiming',
          'REQUIRED':'salon_setup_required','META':'salon_setup_safe_metadata','HOST':'salon_setup_hosting_first',
          'NODE':'salon_setup_waiting_node','KEY':'salon_setup_fetching_key','PENDING':'salon_claim_pending',
          'CONFIRMING':'salon_setup_confirming','PUBLISHING':'salon_setup_publishing','CHECKING':'salon_setup_checking',
          'HOST_FAILED':'salon_publish_failed','LOOKUP':'creative_identity_lookup_failed','MINTING':'creative_identity_minting',
          'RETRY_TITLE':'salon_claim_retry_title','RETRY_WARNING':'salon_claim_retry_warning','RETRY_READY':'salon_claim_retry_ready','CANCEL':'common_cancel','REJECTED':'salon_claim_rejected','OPENED':'salon_setup_opened'}
    for marker,key in sorted(keys.items(),key=lambda x:-len(x[0])):
        args=', mediaError(unavailable)' if marker=='HOST_FAILED' else ''
        snippet=snippet.replace('MSG_'+marker,'Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_'+key+args+')')
    a=s.index('    private void claimIdentity(');b=s.index('    private static final class HostedRestore',a)
    s=s[:a]+snippet+'\n'+s[b:]
    a=s.index('        if (nodeUp && !adoptChecked');b=s.index('        // One-time-per-session setup',a)
    s=s[:a]+s[b:]
    old='        wasNodeUp = nodeUp;';assert s.count(old)==1
    s=s.replace(old,'        if (nodeUp && !adoptChecked && !claiming && !SalonStore.hasIdentity(this)) { checkIdentityAdoption(); }\n'+old)
    old='        cancelHostedRestore();';assert s.count(old)==1
    s=s.replace(old,old+'\n        cancelIdentityClaim();')
    old='        renderEpoch++;';assert s.count(old)==1
    s=s.replace(old,'        cancelIdentityClaim();\n'+old)
    old='        claimBtn.setEnabled(!claiming); if (claiming) claimBtn.setAlpha(0.4f);';assert s.count(old)==1
    s=s.replace(old,'        claimHandleField=handle; claimNameField=name; claimBioField=bio; setClaimEnabled(!claiming);')
    old='        form.addView(claimBtn, lph(52, 0, 10, 0, 0));';assert s.count(old)==1
    s=s.replace(old,old+'\n        if (SalonStore.me(this).has("claimPending")) {\n            claimRetryButton=btn(Language.msg(com.eurobuddha.minimacore.i18n.R.string.l10n_salon_claim_retry_title),false,this::confirmClaimRetry);\n            form.addView(claimRetryButton,lph(48,0,8,0,0));\n        }')
    old='    @Override protected void onResume() {\n        super.onResume();';assert s.count(old)==1
    s=s.replace(old,old+'\n        checkIdentityAdoption();')
    p.write_text(s)
