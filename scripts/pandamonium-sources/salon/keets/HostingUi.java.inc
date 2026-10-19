            case Hosting.TYPE_KEETS:
                card.addView(Design.note(this, "Host your public profile and media on this phone through Keets. No hosting account needed. Keep your node running and connected for others to view it. Copies held by other users may remain available while you are offline.\n\nViewable in Salon and compatible Keets apps, not ordinary browsers. Photos sent privately are not uploaded here.\n\n24 MB total per profile, including media. Existing external links stay with their current hosts."), lp(0, 6, 0, 2));
                try { card.addView(Design.note(this, "Profile usage: " + mibOf(KeetsRef.usage(buildProfileJson())) + " / 24 MB"), lp(0, 6, 0, 2)); }
                catch (Exception full) { card.addView(Design.note(this, full.getMessage()), lp(0, 6, 0, 2)); }
                break;
