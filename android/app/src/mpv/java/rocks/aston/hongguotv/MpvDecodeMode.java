package rocks.aston.hongguotv;

/** A source snapshots this preference; changing it never reconfigures a live decoder. */
enum MpvDecodeMode {
    // Software decoding is the default: the vendor codec service aborts on
    // decoder recreation (every episode switch corrupts the omx heap, Scudo
    // abort in omx@1.0-service) and takes the whole app down through the
    // MediaCodec release path. Software decoding never touches that service,
    // keeps mpv's atomic loadfile replace available for seamless switches,
    // and with bytevc1 tiers filtered server side it plays everything
    // smoothly. Hardware decoding stays available as the explicit "自动解码"
    // choice (mediacodec-copy, fenced stops).
    AUTOMATIC("mpv_auto", "mediacodec-copy"), SOFTWARE("mpv_software", "no");

    final String id, hwdec;
    MpvDecodeMode(String id, String hwdec) { this.id = id; this.hwdec = hwdec; }

    static MpvDecodeMode requested(String id) {
        for (MpvDecodeMode mode : values()) if (mode.id.equals(id)) return mode;
        return null;
    }
    static MpvDecodeMode stored(String id) {
        MpvDecodeMode mode = requested(id);
        return mode == null ? SOFTWARE : mode;
    }
}
