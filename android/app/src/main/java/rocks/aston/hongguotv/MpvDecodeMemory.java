package rocks.aston.hongguotv;

import java.util.HashSet;
import java.util.Set;

/**
 * Remembers which video codecs this device could only present through
 * software decoding, so the next episode skips the broken hardware attempt
 * (and its green flash) instead of rediscovering the failure every source.
 * The memory is per codec: a box whose HEVC decoder is broken can keep
 * decoding AVC in hardware.
 */
final class MpvDecodeMemory {
    static final String STORAGE_KEY = "hwdec_broken_codecs";
    private static final int MAX_CODECS = 8;

    private final Set<String> broken = new HashSet<>();

    MpvDecodeMemory(String stored) {
        if (stored == null) return;
        for (String codec : stored.split(",")) {
            String normalized = normalize(codec);
            if (!normalized.isEmpty()) broken.add(normalized);
        }
    }

    static String normalize(String codec) {
        return codec == null ? "" : codec.trim().toLowerCase(java.util.Locale.ROOT);
    }

    boolean isBroken(String codec) {
        String normalized = normalize(codec);
        return !normalized.isEmpty() && broken.contains(normalized);
    }

    boolean learn(String codec) {
        String normalized = normalize(codec);
        if (normalized.isEmpty() || broken.size() >= MAX_CODECS) return false;
        return broken.add(normalized);
    }

    String store() {
        StringBuilder result = new StringBuilder();
        for (String codec : broken) {
            if (result.length() > 0) result.append(',');
            result.append(codec);
        }
        return result.toString();
    }
}
