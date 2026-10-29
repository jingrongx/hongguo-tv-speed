package rocks.aston.hongguotv;

/**
 * Detects the "whole picture is solid green" hardware decode failure that no
 * decoder error ever reports: the vendor MediaCodec starts, delivers frames
 * and advances the clock, yet every frame decodes to the YUV-green blank
 * (0,128,128). The probe samples the presented surface and, once the broken
 * output is confirmed, the bridge replays the source with software decoding
 * and remembers the codec for future sessions.
 */
final class MpvGreenFramePolicy {
    /** Consecutive broken probes before the picture is declared broken. */
    static final int REQUIRED_GREEN_STREAK = 2;
    /** Total probes per source attempt, bounding the whole check to ~8s. */
    static final int MAX_PROBES = 6;
    static final long FIRST_PROBE_DELAY_MS = 1500;
    static final long PROBE_INTERVAL_MS = 1300;

    private MpvGreenFramePolicy() {}

    /** Only hardware attempts of an automatic session can prove the picture broken. */
    static boolean probeApplies(boolean automaticMode, boolean softwareAttempt, boolean outputReady,
            boolean background, boolean probingSupported) {
        return probingSupported && automaticMode && !softwareAttempt && outputReady && !background;
    }

    /**
     * A frame is provably broken when nearly every sampled pixel is a
     * saturated green the source could not plausibly fill the whole picture
     * with for consecutive probes. Audio-only or dark frames never match.
     */
    static boolean isBrokenFrame(int[] pixels) {
        if (pixels == null || pixels.length == 0) return false;
        int green = 0, total = 0;
        for (int pixel : pixels) {
            total++;
            int red = (pixel >> 16) & 0xff, greenChannel = (pixel >> 8) & 0xff, blue = pixel & 0xff;
            if (greenChannel >= 150 && greenChannel - red >= 60 && greenChannel - blue >= 60) green++;
        }
        return green * 20 >= total * 19;
    }

    static boolean shouldFallback(int greenStreak) {
        return greenStreak >= REQUIRED_GREEN_STREAK;
    }

    static boolean keepProbing(int probesUsed) {
        return probesUsed < MAX_PROBES;
    }
}
