package rocks.aston.hongguotv;

import org.junit.Test;
import static org.junit.Assert.*;

/** Smart decode: broken-output probing decisions and per-codec memory. */
public class MpvSmartDecodeTest {

    @Test
    public void brokenFrameDetectionMatchesSolidYuvGreenOnly() {
        // YUV (0,128,128) renders as saturated green.
        int green = 0xFF00C853; // g=200, r=0, b=83
        int[] solidGreen = new int[100];
        java.util.Arrays.fill(solidGreen, green);
        assertTrue(MpvGreenFramePolicy.isBrokenFrame(solidGreen));

        // Real content: a normal colorful frame.
        int[] frame = new int[100];
        java.util.Arrays.fill(frame, 0xFF3A6EA5);
        assertFalse(MpvGreenFramePolicy.isBrokenFrame(frame));

        // Black frames and audio-only emptiness never count.
        int[] black = new int[100];
        assertFalse(MpvGreenFramePolicy.isBrokenFrame(black));
        assertFalse(MpvGreenFramePolicy.isBrokenFrame(new int[0]));
        assertFalse(MpvGreenFramePolicy.isBrokenFrame(null));

        // Mostly-broken frames above 95% do, mixed ones do not.
        int[] mostlyGreen = new int[100];
        java.util.Arrays.fill(mostlyGreen, green);
        mostlyGreen[3] = 0xFF1A1A1A;
        assertTrue(MpvGreenFramePolicy.isBrokenFrame(mostlyGreen));
        int[] halfGreen = new int[100];
        java.util.Arrays.fill(halfGreen, green);
        for (int i = 50; i < 100; i++) halfGreen[i] = 0xFF1A1A1A;
        assertFalse(MpvGreenFramePolicy.isBrokenFrame(halfGreen));
    }

    @Test
    public void probeOnlyRunsOnHardwareAttemptsOfAutomaticSessions() {
        assertTrue(MpvGreenFramePolicy.probeApplies(true, false, true, false, true));
        // Manual software decoding never probes.
        assertFalse(MpvGreenFramePolicy.probeApplies(false, false, true, false, true));
        // A source that already fell back to software never probes again.
        assertFalse(MpvGreenFramePolicy.probeApplies(true, true, true, false, true));
        // Not ready or backgrounded surfaces cannot be probed.
        assertFalse(MpvGreenFramePolicy.probeApplies(true, false, false, false, true));
        assertFalse(MpvGreenFramePolicy.probeApplies(true, false, true, true, true));
        // API < 26 has no PixelCopy readback.
        assertFalse(MpvGreenFramePolicy.probeApplies(true, false, true, false, false));
    }

    @Test
    public void fallbackNeedsConsecutiveBrokenProbesAndStopsAtTheCap() {
        assertFalse(MpvGreenFramePolicy.shouldFallback(0));
        assertFalse(MpvGreenFramePolicy.shouldFallback(1));
        assertTrue(MpvGreenFramePolicy.shouldFallback(2));
        assertTrue(MpvGreenFramePolicy.shouldFallback(3));

        assertTrue(MpvGreenFramePolicy.keepProbing(0));
        assertTrue(MpvGreenFramePolicy.keepProbing(MpvGreenFramePolicy.MAX_PROBES - 1));
        assertFalse(MpvGreenFramePolicy.keepProbing(MpvGreenFramePolicy.MAX_PROBES));
    }

    @Test
    public void decodeMemoryRemembersBrokenCodecsPerDevice() {
        MpvDecodeMemory memory = new MpvDecodeMemory("hevc, h264");
        assertTrue(memory.isBroken("hevc"));
        assertTrue(memory.isBroken("H264"));
        assertTrue(memory.isBroken(" h264 "));
        assertFalse(memory.isBroken("av1"));
        assertFalse(memory.isBroken(null));
        assertFalse(memory.isBroken(""));
    }

    @Test
    public void decodeMemoryLearnsNormalizedCodecsAndRoundTrips() {
        MpvDecodeMemory memory = new MpvDecodeMemory(null);
        assertTrue(memory.learn("HEVC"));
        assertTrue(memory.isBroken("hevc"));
        assertFalse(memory.learn("hevc"));
        assertEquals("hevc", memory.store());

        MpvDecodeMemory restored = new MpvDecodeMemory(memory.store());
        assertTrue(restored.isBroken("hevc"));
        assertFalse(restored.isBroken("h264"));
    }

    @Test
    public void decodeMemoryIsBounded() {
        MpvDecodeMemory memory = new MpvDecodeMemory(null);
        for (int i = 0; i < 20; i++) memory.learn("codec" + i);
        assertEquals(MpvDecodeMemory.normalize("x"), "x");
        assertTrue(memory.store().split(",").length <= 8);
    }
}
