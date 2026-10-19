    private void quickStartKeets() {
        new android.app.AlertDialog.Builder(this).setTitle("Host on this device")
                .setMessage("Your public Salon profile and media will be served from this phone through Keets. Keep your node running and connected. Viewers need Salon or a compatible Keets app. Your total profile allowance is 24 MB.")
                .setNegativeButton("Cancel", null).setPositiveButton("Use this device", (d,w) -> {
                    Hosting.Profile p=Hosting.Profile.fresh(Hosting.TYPE_KEETS);
                    Hosting.put(p.j,"name","This device (Keets)");saveHost(p);HostingStore.setDefault(this,p.id());
                    render();testProfile(p);
                }).show();
    }

