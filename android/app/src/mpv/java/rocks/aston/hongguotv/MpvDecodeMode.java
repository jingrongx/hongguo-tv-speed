package rocks.aston.hongguotv;

/** A source snapshots this preference; changing it never reconfigures a live decoder. */
enum MpvDecodeMode {
    // Automatic decoding (hardware via mediacodec-copy) is the default: on
    // most TVs it is far smoother, and the rebuilt mpv stack no longer
    // trips the vendor codec service on this generation of devices. When a
    // decoder still fails to start or dies mid-play, the bridge falls back
    // to software decoding for that source once before giving up, so the
    // user sees a working picture instead of an error.
    AUTOMATIC("mpv_auto", "mediacodec-copy"), SOFTWARE("mpv_software", "no");

    final String id, hwdec;
    MpvDecodeMode(String id, String hwdec) { this.id = id; this.hwdec = hwdec; }

    static MpvDecodeMode requested(String id) {
        for (MpvDecodeMode mode : values()) if (mode.id.equals(id)) return mode;
        return null;
    }
    static MpvDecodeMode stored(String id) {
        MpvDecodeMode mode = requested(id);
        return mode == null ? AUTOMATIC : mode;
    }
}
