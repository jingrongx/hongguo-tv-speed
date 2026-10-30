package rocks.aston.hongguotv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import org.json.JSONObject;

/** One libmpv client with explicit event fences between playback sessions. */
public final class MpvPlayerBridge implements NativePlayerAdapter, SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WebView web;
    private final FrameLayout holder;
    private final SurfaceView surface;
    private final SharedPreferences preferences;
    private MpvDecodeMemory decodeMemory;
    // One small probe bitmap per broken-output scan: PixelCopy scales the
    // presented surface into it, keeping the check off the decoder path.
    private Bitmap probeBitmap;
    private boolean probeInFlight;
    // Bridge calls may arrive from WebView threads while all player state is
    // serialized onto the main thread.
    private volatile Session session;
    private MPVLib player;
    private boolean playerSurfaceAttached;
    private boolean playerQuiescent = true;
    private final MpvTransitionState<Session> transition = new MpvTransitionState<>();
    private MPVLib stoppingPlayer;
    private final MpvStopFence stopFence = new MpvStopFence();
    private long stopCommandSequence;
    private Runnable stopTimeout;
    private long stopFenceStartedAt;
    private long lastStopFenceMs;
    private volatile long generation;
    private boolean destroyed, background, surfaceReady;
    private long surfaceEpoch, stopSurfaceEpoch = -1;
    private double volume = 1, speed = 1;
    private boolean muted = true;
    // Levels of different dramas vary widely; dynaudnorm evens them out in
    // real time without a two-pass analysis. Off switches back to raw audio.
    private volatile boolean loudness = true;
    private float left, top, width, height;
    private volatile String diagnostic = "{\"engine\":\"mpv\"}";

    private interface SessionAction { void run(Session target); }

    private static final class Session {
        final long generation;
        final String id, source;
        final JSONObject data;
        final MpvDecodeMode mode;
        final MpvOutputState output = new MpvOutputState();
        MPVLib player;
        boolean outputReady, wantsPlay, terminal, pendingSeek;
        // Set when this source already fell back from hardware to software
        // decoding, preventing a fallback loop on genuinely dead sources.
        boolean swFallback;
        int greenProbes, greenStreak;
        Runnable probe;
        // Decode-stall watch: sampled drop counters (decoder + VO) every five
        // seconds. Two heavy-drop windows in a row on one decode path flip
        // the session to the other path once — hardware that greens screens
        // lands in software, software that cannot keep up lands in hardware.
        long dropSampleAt, dropBase;
        int stallStrikes;
        // Gapless queue: the next episode of the same drama appended via
        // loadfile append-play. mpv advances on its own at end-of-file; the
        // bridge reports an auto_next event and keeps the session alive.
        String queuedId;
        JSONObject queuedData;
        // Set synchronously on the JS bridge thread (where queueNext runs)
        // so a second timeupdate cannot double-append before the posted
        // command lands on the UI thread.
        volatile boolean queuePending;
        boolean firstFileLoaded;
        long position, duration;
        long loadRequestedAt, fileLoadedAt, outputReadyAt;
        String decoder = "", state = "preparing";
        String errorKind = "";
        int errorCode;
        Runnable timeout;
        Session(long generation, String id, String source, JSONObject data, MpvDecodeMode mode) {
            this.generation = generation;
            this.id = id; this.source = source; this.data = data;
            this.mode = mode;
            wantsPlay = !data.optBoolean("paused", false);
            position = PlaybackStart.positionMs(data.optDouble("start_position", 0), false);
        }
    }

    MpvPlayerBridge(WebView web, FrameLayout holder) {
        this.web = web; this.holder = holder;
        preferences = web.getContext().getSharedPreferences("hongguo_mpv_playback", Context.MODE_PRIVATE);
        decodeMemory = new MpvDecodeMemory(preferences.getString(MpvDecodeMemory.STORAGE_KEY, null));
        surface = new SurfaceView(web.getContext());
        surface.setFocusable(false);
        surface.getHolder().addCallback(this);
        holder.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        // Keep the SurfaceView alive between sources. Alpha hides the native
        // layer while the shared libmpv client is fenced between sources.
        holder.setVisibility(View.VISIBLE);
        holder.setAlpha(0f);
        web.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> applyBoundsWhenIdle());
        handler.postDelayed(progress, 500);
    }

    @JavascriptInterface public boolean isAvailable() { return true; }
    @JavascriptInterface public String engine() { return "mpv"; }
    @JavascriptInterface public String diagnostics() { return diagnostic; }
    @JavascriptInterface public String decoderMode() {
        String stored = preferences.getString("mpv_decode_mode", null);
        // Reverse of the 0.2.139 migration: that release forced software
        // decoding because one vendor's codec service corrupted its own heap
        // during decoder recreation. The rebuilt mpv stack and real-world
        // testing show hardware decoding is smooth and stable on current
        // devices, so restore the automatic default once. A choice made after
        // this point sticks because the migration flag is cleared here.
        if (preferences.getBoolean("mpv_auto_migrated", false)) {
            if ("mpv_software".equals(stored)) {
                preferences.edit().remove("mpv_decode_mode").remove("mpv_auto_migrated").apply();
                stored = null;
            } else {
                preferences.edit().putBoolean("mpv_auto_migrated", false).apply();
            }
        }
        return MpvDecodeMode.stored(stored).id;
    }
    /**
     * Append the next episode of the same drama to mpv's playlist. mpv plays
     * it itself at end-of-file with gapless audio; the bridge reports
     * auto_next so the UI can advance its bookkeeping without reloading.
     * Returns false when the session cannot take a queue entry (too late,
     * already queued, not ready) — the caller keeps its fallback path.
     */
    @JavascriptInterface public boolean queueNext(String source, String id, String dataJson) {
        Session target = session;
        if (destroyed || target == null || !current(target) || target.player == null) return false;
        if (target.terminal || target.queuedId != null || target.queuePending || !target.outputReady) return false;
        if (source == null || source.isEmpty()) return false;
        // Same contract as a foreground source: only the APK's own local
        // media endpoint may be opened.
        if (!MediaUrlPolicy.valid(source)) { gaplessLog("queueNext rejected: invalid url " + source); return false; }
        final JSONObject data;
        try { data = dataJson == null ? new JSONObject() : new JSONObject(dataJson); }
        catch (Exception ignored) { return false; }
        final Session queued = target;
        final String queuedSource = source;
        // Every other bridge method posts its mpv work: MPVLib enforces the
        // UI thread, and this call arrives on the WebView bridge thread. The
        // command itself therefore runs asynchronously; the boolean only
        // reflects the synchronous session decision.
        target.queuePending = true;
        gaplessLog("queueNext accepted ep=" + data.optInt("episode", 0));
        handler.post(() -> {
            if (destroyed || session != queued || queued.queuedId != null || !current(queued)) {
                queued.queuePending = false;
                return;
            }
            try {
                queued.player.command(new String[]{"loadfile", queuedSource, "append-play"});
                queued.queuedId = id;
                queued.queuedData = data;
                gaplessLog("queueNext appended ep=" + data.optInt("episode", 0));
                JSONObject detail = new JSONObject();
                try { detail.put("kind", "queue_next").put("episode", data.optInt("episode", 0)); } catch (Exception ignored) {}
                event(queued, "info", detail);
            } catch (RuntimeException failure) {
                gaplessLog("queueNext command failed: " + failure);
                queued.queuePending = false;
            }
        });
        return true;
    }

    @JavascriptInterface public boolean safeStart() { return false; }
    @JavascriptInterface public void setDecoderMode(String value) {
        MpvDecodeMode mode = MpvDecodeMode.requested(value);
        if (mode == null) return;
        // An explicit choice is also the reset path: a firmware update may
        // have fixed the vendor decoder, so forget the learned codecs.
        preferences.edit().putString("mpv_decode_mode", mode.id).remove(MpvDecodeMemory.STORAGE_KEY)
            .putBoolean("mpv_hwdec_rejected", false).putBoolean("mpv_sw_stall_upgraded", false).apply();
        decodeMemory = new MpvDecodeMemory(null);
    }

    @JavascriptInterface public void showLicenses() {
        handler.post(() -> {
            if (destroyed) return;
            try (java.io.InputStream input = web.getContext().getAssets().open("mpv-notices.txt")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                TextView text = new TextView(web.getContext());
                text.setText(bytes.toString("UTF-8")); text.setTextSize(16); text.setPadding(24,24,24,24);
                ScrollView scroll = new ScrollView(web.getContext()); scroll.setFocusable(true); scroll.addView(text);
                new AlertDialog.Builder(web.getContext()).setTitle("MPV 开源许可")
                    .setView(scroll).setPositiveButton("关闭", (dialog, which) -> dialog.dismiss()).show();
            } catch (java.io.IOException ignored) {
                new AlertDialog.Builder(web.getContext()).setMessage("许可文件暂时无法读取，请查看对应版本的库源码附件。")
                    .setPositiveButton("关闭", null).show();
            }
        });
    }

    @JavascriptInterface public void play(String id, String payload) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,80}") || payload == null || payload.length() > 65536) return;
        try {
            JSONObject data = new JSONObject(payload);
            String source = data.optString("source");
            handler.post(() -> {
                if (destroyed) return;
                requestPlay(id, source, data);
            });
        } catch (Exception ignored) {
            handler.post(() -> send(id, "error", new JSONObject()));
        }
    }

    private void requestPlay(String id, String source, JSONObject data) {
        if (stopFence.isActive()) {
            transition.queue(new Session(generation, id, source, data, MpvDecodeMode.stored(decoderMode())));
            return;
        }
        if (session == null && (player == null || playerQuiescent)) {
            generation++;
            installSession(new Session(generation, id, source, data, MpvDecodeMode.stored(decoderMode())));
            return;
        }
        // Atomic loadfile replace for every engine: mpv keeps the decoded
        // picture on screen while the next source loads, so episode switches
        // show no black gap and no spinner. Hardware decoders are handed over
        // inside mpv during the replace; devices whose vendor codec service
        // cannot survive that handoff crash once, and the activity's crash
        // self-heal demotes them to software decoding (which never touches
        // that service) for every session afterwards.
        if (player != null && !playerQuiescent && session != null) {
            Session old = session;
            generation++;
            old.terminal = true;
            if (old.timeout != null) handler.removeCallbacks(old.timeout);
            cancelProbe(old);
            old.player = null;
            installSession(new Session(generation, id, source, data, MpvDecodeMode.stored(decoderMode())));
            return;
        }
        requestStop(id, source, data);
    }

    private void installSession(Session target) {
        Boolean playIntent = transition.takePlayIntent();
        if (playIntent != null) {
            target.wantsPlay = playIntent;
        }
        session = target;
        if (!MediaUrlPolicy.valid(target.source)) { fail(target, "source"); return; }
        // Apply the latest geometry before attaching a native player. During a
        // fenced source replacement, WebView can report bounds while the old
        // Surface is still stopping; applying them here keeps that resize
        // away from the new libmpv/Surface binding window.
        if (!applyBounds()) { fail(target, "display"); return; }
        double rate = target.data.optDouble("speed", speed);
        if (finite(rate) && rate > 0 && rate <= 4) speed = rate;
        holder.setVisibility(View.VISIBLE);
        holder.setAlpha(1f);
        armTimeout(target);
        event(target, "buffering", null);
        start(target);
    }

    private boolean current(Session target) {
        return target != null && !destroyed && session == target
            && target.generation == generation && !target.terminal;
    }
    private void start(Session target) {
        if (!MpvStartPolicy.mayStart(current(target), target.player != null,
                surface.getHolder().getSurface().isValid())) return;
        try {
            MPVLib active = ensurePlayer();
            if (active == null) { fail(target, "initialization"); return; }
            target.player = active;
            if (!playerSurfaceAttached) {
                active.attachSurface(surface.getHolder().getSurface());
                playerSurfaceAttached = true;
            }
            target.output.attached = playerSurfaceAttached;
            android.graphics.Rect frame = surface.getHolder().getSurfaceFrame();
            surfaceSize(target, frame.width(), frame.height());
            active.setPropertyString("android-surface-size", frame.width() + "x" + frame.height());
            active.setPropertyString("hwdec", target.mode.hwdec);
            target.pendingSeek = target.position > 0;
            active.setPropertyString("force-window", "yes");
            active.setPropertyBoolean("mute", muted);
            active.setPropertyDouble("volume", volume * 100);
            active.setPropertyDouble("speed", speed);
            active.setPropertyString("af", loudness ? "dynaudnorm" : "");
            // Match MiniReel's open(play: false): a new source must not emit
            // audio while its demuxer and video output are being replaced.
            // FILE_LOADED below is the only point at which the play intent is
            // applied to this session.
            active.setPropertyBoolean("pause", true);
            // The previous source reached the stop fence before this loadfile.
            target.loadRequestedAt = SystemClock.uptimeMillis();
            active.command(new String[]{"loadfile", target.source, "replace"});
            target.output.openRequested = true;
            playerQuiescent = false;
        } catch (LinkageError | RuntimeException failure) { fail(target, "initialization"); }
    }

    /** Create/configure the player client, retained across fenced source changes. */
    private MPVLib ensurePlayer() {
        if (player != null) return player;
        final MPVLib active = MPVLib.create(web.getContext());
        if (active == null) return null;
        try {
            // Match media_kit's safe automatic Android path. Users can retain
            // software decoding if their vendor decoder produces bad frames.
            option(active, "config", "no"); option(active, "terminal", "no");
            option(active, "vo", "gpu"); option(active, "gpu-context", "android");
            option(active, "opengl-es", "yes"); option(active, "hwdec", MpvDecodeMode.stored(decoderMode()).hwdec);
            option(active, "keepaspect", "yes"); option(active, "panscan", "0");
            option(active, "video-align-x", "0"); option(active, "video-align-y", "0");
            option(active, "scale", "bilinear"); option(active, "dscale", "bilinear");
            option(active, "dither", "no"); option(active, "correct-downscaling", "no");
            option(active, "linear-downscaling", "no"); option(active, "sigmoid-upscaling", "no");
            option(active, "ao", "opensles,audiotrack"); option(active, "audiotrack-pcm-float", "no");
            option(active, "keep-open", "yes"); option(active, "idle", "yes");
            // Open and demux the queued next episode while the current one
            // plays: without this mpv only touches the appended file at
            // end-of-file, and the decoder bring-up costs about a second of
            // freeze on TV boxes.
            option(active, "prefetch-playlist", "yes");
            option(active, "force-window", "no"); option(active, "osd-level", "0");
            option(active, "input-default-bindings", "no"); option(active, "input-vo-keyboard", "no");
            option(active, "sid", "no"); option(active, "cache", "yes");
            option(active, "demuxer-max-bytes", "32MiB"); option(active, "demuxer-max-back-bytes", "8MiB");
            option(active, "network-timeout", "15");
            player = active;
            active.addObserver(new MPVLib.EventObserver() {
                private void post(SessionAction action) {
                    if (stopFence.isActive() && stoppingPlayer == active) {
                        stopFence.onEvent();
                        return;
                    }
                    // Capture the session when libmpv delivers the callback.
                    // Reading the mutable field later can attribute a delayed
                    // old-source event to a newer card.
                    final Session observed = session;
                    final long observedGeneration = generation;
                    handler.post(() -> {
                        // Check the identity and generation before entering
                        // any callback body. The second current() check in
                        // guarded() remains the final guard if another main
                        // thread transition happened before execution.
                        if (observed == null || observed.generation != observedGeneration
                                || observed.terminal || session != observed || observed.player != active) return;
                        guarded(observed, () -> action.run(observed));
                    });
                }
                @Override public void error() { post(target -> fail(target, "native_event")); }
                @Override public void outputMessage(int component, int level) {
                    post(target -> { target.output.message(component, level); updateDiagnostic(target, null); });
                }
                @Override public void endFile(int reason, int error) {
                    if (stopFence.isActive() && stoppingPlayer == active) {
                        stopFence.onEndFile();
                        return;
                    }
                    post(target -> {
                        target.output.endFileSeen = true;
                        target.output.endFileReason = reason;
                        target.output.endFileError = error;
                        updateDiagnostic(target, null);
                        // Preserve the existing readiness gate. Early errors
                        // stay visible in diagnostics and follow the existing
                        // timeout path instead of changing playback timing.
                        if (!target.output.loaded) return;
                        if (reason == 0 && target.outputReady) {
                            gaplessLog("endFile reason=0 queued=" + (target.queuedId == null ? "no" : "yes"));
                            if (target.queuedId != null) {
                                // mpv already moved to the queued episode on
                                // its own: report the last progress (history
                                // writes use it) and keep the session alive —
                                // the auto_next event follows on file-loaded.
                                reportProgress(target);
                                return;
                            }
                            finish(target);
                        }
                        else if ((reason == 4 || error < 0) && target.outputReady) {
                            gaplessLog("endFile reason=" + reason + " error=" + error);
                            fail(target, "media", error);
                        }
                    });
                }
                @Override public void commandReply(long requestId, int error) {
                    if (!stopFence.isActive() || stoppingPlayer != active) return;
                    if (stopFence.onCommandReply(requestId, error) && stopFence.failed())
                        finishStopBoundary(false);
                }
                @Override public void eventsDrained() {
                    if (stopFence.isActive() && stoppingPlayer == active && stopFence.onEventsDrained())
                        finishStopBoundary(true);
                }
                @Override public void eventProperty(String name, boolean value) {
                    post(target -> {
                        // A queued next episode owns the end-of-file boundary:
                        // mpv advances to it on its own (keep-open only holds
                        // the LAST playlist entry). Finishing here used to
                        // kill the session before the queued file could play,
                        // which silently disabled the whole gapless queue.
                        if ("eof-reached".equals(name) && value && target.outputReady) {
                            gaplessLog("eof-reached queued=" + (target.queuedId == null ? "no" : "yes:" + target.queuedId));
                            if (target.queuedId == null) finish(target);
                        }
                        else if ("vo-configured".equals(name)) { target.output.configured = value; rendered(target); }
                        else if ("paused-for-cache".equals(name) && target.outputReady && target.wantsPlay && !background)
                            MpvPlayerBridge.this.event(target, value ? "buffering" : "playing", null);
                        else if ("pause".equals(name) && target.outputReady && !background)
                            MpvPlayerBridge.this.event(target, value ? "paused" : "playing", null);
                    });
                }
                @Override public void event(int value) {
                    post(target -> {
                        if (value == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                            boolean queuedAdvance = target.firstFileLoaded && target.queuedId != null;
                            if (queuedAdvance) gaplessLog("fileLoaded queued advance ep=" + (target.queuedData == null ? "?" : String.valueOf(target.queuedData.optInt("episode", 0))));
                            target.output.loaded = true;
                            target.fileLoadedAt = SystemClock.uptimeMillis();
                            if (queuedAdvance) {
                                target.firstFileLoaded = true;
                                String advancedId = target.queuedId;
                                JSONObject advancedData = target.queuedData;
                                target.queuedId = null; target.queuedData = null;
                                // Re-arm the per-file state machine: readiness,
                                // green probe and stall watch all restart for
                                // the new file on this same session. The
                                // output timeout must come back too — without
                                // it a queued file that never renders hangs
                                // the session with no recovery path.
                                target.output.loaded = true;
                                target.outputReady = false;
                                target.output.restarted = false;
                                target.greenProbes = 0; target.greenStreak = 0;
                                target.dropSampleAt = 0; target.stallStrikes = 0;
                                target.pendingSeek = false;
                                armTimeout(target);
                                MpvPlayerBridge.this.event(target, "auto_next", advancedData == null ? new JSONObject() : advancedData);
                            }
                            target.firstFileLoaded = true;
                            // A codec this device could only present in software
                            // skips the broken hardware attempt before any green
                            // frame can reach the screen.
                            if (target.mode == MpvDecodeMode.AUTOMATIC && !target.swFallback
                                    && decodeMemory.isBroken(currentCodec(target))) {
                                fail(target, "green_output");
                                return;
                            }
                            if (target.pendingSeek) { active.command(new String[]{"seek", Double.toString(target.position / 1000.0), "absolute+exact"}); target.pendingSeek = false; }
                            applyPlayState(target);
                            updateDiagnostic(target, null);
                        } else if (value == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
                            target.output.restarted = true; rendered(target);
                        }
                    });
                }
            });
            active.init();
            active.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            active.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            active.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            active.observeProperty("vo-configured", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            return active;
        } catch (LinkageError | RuntimeException failure) {
            player = null;
            try { active.destroy(); } catch (RuntimeException ignored) {}
            return null;
        }
    }
    private static void option(MPVLib player, String name, String value) {
        if (player.setOptionString(name, value) < 0) throw new IllegalStateException("MPV option rejected: " + name);
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private void applyPlayState(Session target) {
        if (!current(target) || target.player == null) return;
        target.player.setPropertyBoolean("pause", background || !target.wantsPlay);
    }
    private void guarded(Session target, Runnable action) {
        if (!current(target)) return;
        try { action.run(); } catch (RuntimeException failure) { fail(target, "player"); }
    }
    private void armTimeout(Session target) {
        if (target.timeout != null) handler.removeCallbacks(target.timeout);
        if (background || target.outputReady) return;
        target.timeout = () -> { if (current(target) && !target.outputReady && !background) fail(target, "output_timeout"); };
        handler.postDelayed(target.timeout, 25000);
    }
    private void rendered(Session target) {
        if (!current(target) || target.player == null) return;
        MPVLib player = target.player;
        Integer w = player.getPropertyInt("dwidth"), h = player.getPropertyInt("dheight");
        target.output.width = w == null ? 0 : w; target.output.height = h == null ? 0 : h;
        target.output.vo = property(player, "current-vo");
        target.output.configured = Boolean.TRUE.equals(player.getPropertyBoolean("vo-configured"));
        if (!target.output.ready(surface.getHolder().getSurface().isValid())) return;
        String decoder = player.getPropertyString("video-codec");
        target.decoder = decoder == null ? "" : decoder;
        if (!target.outputReady) {
            target.outputReady = true;
            target.outputReadyAt = SystemClock.uptimeMillis();
            handler.removeCallbacks(target.timeout);
            armGreenProbe(target);
            JSONObject detail = new JSONObject();
            try { detail.put("width", w).put("height", h); } catch (Exception ignored) {}
            // The shared adapter's frame event means presentation-ready here.
            // libmpv has no physical first-frame callback; report that honestly.
            event(target, "frame", detail);
        }
        if (!background) event(target, target.wantsPlay ? "playing" : "paused", null);
    }
    private void finish(Session target) {
        if (!current(target)) return;
        gaplessLog("finish -> ended");
        reportProgress(target);
        event(target, "ended", null); target.terminal = true;
        try { target.player.setPropertyBoolean("pause", true); } catch (RuntimeException ignored) {}
        handler.removeCallbacks(target.timeout);
    }
    private static void gaplessLog(String message) {
        android.util.Log.i("HongguoMPV", message);
    }
    /** Broken hardware output is provable only from the presented pixels. */
    private static boolean greenProbeSupported() {
        return Build.VERSION.SDK_INT >= 26;
    }
    private void armGreenProbe(Session target) {
        cancelProbe(target);
        if (!MpvGreenFramePolicy.probeApplies(target.mode == MpvDecodeMode.AUTOMATIC, target.swFallback,
                target.outputReady, background, greenProbeSupported())) return;
        target.greenProbes = 0;
        target.greenStreak = 0;
        target.probe = () -> probeGreenFrame(target);
        handler.postDelayed(target.probe, MpvGreenFramePolicy.FIRST_PROBE_DELAY_MS);
    }
    private void cancelProbe(Session target) {
        if (target.probe != null) { handler.removeCallbacks(target.probe); target.probe = null; }
    }
    private void releaseProbeBitmap() {
        if (probeBitmap != null) { probeBitmap.recycle(); probeBitmap = null; }
    }
    private void probeGreenFrame(Session target) {
        if (!greenProbeSupported()) return;
        if (!current(target) || !target.outputReady || background) return;
        if (target.player != null) {
            Boolean paused = target.player.getPropertyBoolean("pause");
            Boolean caching = target.player.getPropertyBoolean("paused-for-cache");
            if ((paused != null && paused) || (caching != null && caching)) {
                // The picture is not advancing; a probe would only waste budget.
                if (MpvGreenFramePolicy.keepProbing(target.greenProbes))
                    handler.postDelayed(target.probe, MpvGreenFramePolicy.PROBE_INTERVAL_MS);
                return;
            }
            String hwdec = target.player.getPropertyString("hwdec-current");
            if (hwdec == null || hwdec.isEmpty() || "no".equals(hwdec)) return;
        }
        if (probeInFlight) {
            handler.postDelayed(target.probe, MpvGreenFramePolicy.PROBE_INTERVAL_MS);
            return;
        }
        try {
            probeInFlight = true;
            requestPixelCopy(target);
        } catch (RuntimeException ignored) {
            probeInFlight = false;
            releaseProbeBitmap();
        }
    }
    /** Kept separate so class verification only touches PixelCopy on API 26+. */
    private void requestPixelCopy(Session target) {
        if (probeBitmap == null) probeBitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        Bitmap bitmap = probeBitmap;
        android.view.PixelCopy.request(surface, bitmap, result -> {
            probeInFlight = false;
            if (!current(target) || !target.outputReady) { releaseProbeBitmap(); return; }
            target.greenProbes++;
            boolean broken = result == android.view.PixelCopy.SUCCESS
                && MpvGreenFramePolicy.isBrokenFrame(sampleProbeBitmap(bitmap));
            target.greenStreak = broken ? target.greenStreak + 1 : 0;
            updateDiagnostic(target, null);
            if (broken && MpvGreenFramePolicy.shouldFallback(target.greenStreak)) {
                releaseProbeBitmap();
                fail(target, "green_output");
                return;
            }
            if (MpvGreenFramePolicy.keepProbing(target.greenProbes))
                handler.postDelayed(target.probe, MpvGreenFramePolicy.PROBE_INTERVAL_MS);
            else releaseProbeBitmap();
        }, handler);
    }
    private static int[] sampleProbeBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return new int[0];
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] all = new int[width * height];
        bitmap.getPixels(all, 0, width, 0, 0, width, height);
        return all;
    }
    private String currentCodec(Session target) {
        if (target.player != null) {
            String codec = target.player.getPropertyString("video-codec");
            if (codec == null || codec.isEmpty()) codec = target.player.getPropertyString("video-format");
            if (codec != null && !codec.isEmpty()) return codec;
        }
        return target.decoder;
    }
    private void learnBrokenCodec(Session target) {
        String codec = MpvDecodeMemory.normalize(currentCodec(target));
        if (codec.isEmpty() || !decodeMemory.learn(codec)) return;
        preferences.edit().putString(MpvDecodeMemory.STORAGE_KEY, decodeMemory.store()).apply();
    }
    private void fail(Session target, String kind) {
        fail(target, kind, 0);
    }
    private void fail(Session target, String kind, int code) {
        if (!current(target)) return;
        gaplessLog("fail kind=" + kind + " code=" + code);
        cancelProbe(target);
        // Hardware decoding must never leave a dead screen: when the decoder
        // fails to start, dies mid-play, or provably presents a broken
        // (solid green) picture, retry the same source in software decoding
        // once. Only a software failure is reported upward.
        if (target.mode == MpvDecodeMode.AUTOMATIC && !target.swFallback
                && (kind.equals("initialization") || kind.equals("native_event")
                    || kind.equals("media") || kind.equals("output_timeout")
                    || kind.equals("green_output") || kind.equals("hw_stall"))) {
            if (kind.equals("green_output")) {
            learnBrokenCodec(target);
            preferences.edit().putBoolean("mpv_hwdec_rejected", true).apply();
        }
            JSONObject detail = new JSONObject();
            try { detail.put("kind", "hwdec_fallback").put("code", code); } catch (Exception ignored) {}
            event(target, "info", detail);
            dispose(target);
            Session retry = new Session(target.generation, target.id, target.source, target.data, MpvDecodeMode.SOFTWARE);
            retry.swFallback = true;
            retry.wantsPlay = target.wantsPlay || target.outputReady;
            retry.position = target.position;
            installSession(retry);
            return;
        }
        JSONObject detail = new JSONObject();
        target.errorKind = kind; target.errorCode = code;
        try { detail.put("kind", kind).put("code", code); } catch (Exception ignored) {}
        event(target, "error", detail); target.terminal = true;
        dispose(target); holder.setVisibility(View.VISIBLE); holder.setAlpha(0f);
    }
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            Session target = session;
            if (target != null && current(target) && target.player != null) guarded(target, () -> {
                if (!target.outputReady) rendered(target);
                if (target.outputReady) { reportProgress(target); checkDecodeStall(target); }
                else updateDiagnostic(target, null);
            });
            handler.postDelayed(this, 500);
        }
    };
    private void reportProgress(Session target) {
        if (target.player == null) return;
        Double position = target.player.getPropertyDouble("time-pos"), duration = target.player.getPropertyDouble("duration");
        if (position != null && finite(position)) target.position = Math.max(0, Math.round(position * 1000));
        if (duration != null && finite(duration)) target.duration = Math.max(0, Math.round(duration * 1000));
        updateDiagnostic(target, null);
        web.evaluateJavascript("window.HongguoNativePlayback&&window.HongguoNativePlayback.progress(" + JSONObject.quote(target.id) + "," + target.position + "," + target.duration + ")", null);
    }

    /** Combined decoder + output dropped frames since the file started. */
    private long totalDrops(Session target) {
        Double decoderDrops = target.player.getPropertyDouble("frame-drop-count");
        Double voDrops = target.player.getPropertyDouble("vo-drop-frame-count");
        return Math.round((decoderDrops == null ? 0 : decoderDrops) + (voDrops == null ? 0 : voDrops));
    }

    /**
     * Steady-state decode quality watch. Two consecutive five-second windows
     * losing more than ~6 frames per second mean the current decode path
     * cannot keep up with this device: flip to the other path once. The
     * green-screen probe on the hardware path (and its per-codec memory)
     * guarantees the flip never lands on a broken picture.
     */
    private void checkDecodeStall(Session target) {
        if (target.player == null || !target.outputReady || background || target.terminal) return;
        Boolean paused = target.player.getPropertyBoolean("pause");
        if (paused != null && paused) { target.dropSampleAt = 0; return; }
        long now = SystemClock.uptimeMillis();
        if (target.dropSampleAt == 0) { target.dropSampleAt = now; target.dropBase = totalDrops(target); return; }
        if (now - target.dropSampleAt < 5000) return;
        long delta = totalDrops(target) - target.dropBase;
        target.dropSampleAt = now; target.dropBase = totalDrops(target);
        if (delta < 30) { target.stallStrikes = 0; return; }
        target.stallStrikes++;
        if (target.stallStrikes < 2) return;
        target.stallStrikes = 0;
        String hwdec = target.player.getPropertyString("hwdec-current");
        boolean hardware = hwdec != null && !hwdec.isEmpty() && !"no".equals(hwdec);
        if (target.mode == MpvDecodeMode.AUTOMATIC && !target.swFallback && hardware) {
            fail(target, "hw_stall");
        } else if (target.mode == MpvDecodeMode.SOFTWARE && !target.swFallback) {
            upgradeSoftStall(target);
        }
    }

    /**
     * A device stuck in software decoding (manual choice or vendor
     * migration) that cannot keep up gets one automatic ride up to
     * automatic mode. Devices whose hardware decoder greens screens carry
     * the mpv_hwdec_rejected flag and stay in software.
     */
    private void upgradeSoftStall(Session target) {
        if (preferences.getBoolean("mpv_hwdec_rejected", false)) return;
        if (preferences.getBoolean("mpv_sw_stall_upgraded", false)) return;
        preferences.edit()
            .putBoolean("mpv_sw_stall_upgraded", true)
            .putString("mpv_decode_mode", MpvDecodeMode.AUTOMATIC.id)
            .apply();
        JSONObject detail = new JSONObject();
        try { detail.put("kind", "sw_stall_upgrade"); } catch (Exception ignored) {}
        event(target, "info", detail);
        dispose(target);
        Session retry = new Session(target.generation, target.id, target.source, target.data, MpvDecodeMode.AUTOMATIC);
        retry.wantsPlay = target.wantsPlay || target.outputReady;
        retry.position = target.position;
        installSession(retry);
    }
    private void updateDiagnostic(Session target, JSONObject detail) {
        try {
            JSONObject fields = new JSONObject().put("engine", "mpv").put("state", target.state)
                .put("surface", "SurfaceView").put("mode", target.mode.id).put("hwdec_requested", target.mode.hwdec).put("output_ready", target.outputReady)
                .put("frame_signal", "playback-restart").put("video_decoder", target.decoder)
                .put("width", target.output.width).put("height", target.output.height).put("speed", speed)
                .put("restart_seen", target.output.restarted).put("vo_configured", target.output.configured)
                .put("video_output", target.output.vo).put("surface_width", target.output.surfaceWidth)
                .put("surface_height", target.output.surfaceHeight)
                .put("surface_attached", target.output.attached && surface.getHolder().getSurface().isValid())
                .put("surface_visible", holder.getVisibility() == View.VISIBLE && surface.isShown())
                .put("output_messages", target.output.messages())
                .put("open_requested", target.output.openRequested)
                .put("file_loaded", target.output.loaded)
                .put("green_probe", target.greenStreak > 0 ? "broken" : target.greenProbes > 0 ? "ok" : "idle")
                .put("green_probe_count", target.greenProbes)
                .put("learned_sw_codecs", decodeMemory.store())
                .put("last_stop_fence_ms", lastStopFenceMs)
                .put("end_file_seen", target.output.endFileSeen);
            if (target.loadRequestedAt > 0) {
                fields.put("load_to_file_loaded_ms", target.fileLoadedAt > 0 ? target.fileLoadedAt - target.loadRequestedAt : -1)
                    .put("load_to_output_ready_ms", target.outputReadyAt > 0 ? target.outputReadyAt - target.loadRequestedAt : -1);
            }
            if (target.output.endFileSeen) {
                fields.put("end_file_reason", target.output.endFileReason)
                    .put("end_file_error", target.output.endFileError);
            }
            if (target.player != null) {
                MPVLib player = target.player;
                target.decoder = property(player, "video-codec");
                fields.put("video_decoder", target.decoder).put("hwdec_current", property(player, "hwdec-current"));
                Integer decodedDrops = player.getPropertyInt("decoder-frame-drop-count"), outputDrops = player.getPropertyInt("frame-drop-count");
                Double avsync = player.getPropertyDouble("avsync");
                Double cacheDuration = player.getPropertyDouble("demuxer-cache-duration");
                if (decodedDrops != null && decodedDrops >= 0) fields.put("decoder_frame_drop_count", decodedDrops);
                if (outputDrops != null && outputDrops >= 0) fields.put("frame_drop_count", outputDrops);
                if (avsync != null && finite(avsync)) fields.put("avsync", avsync);
                if (cacheDuration != null && finite(cacheDuration) && cacheDuration >= 0)
                    fields.put("demuxer_cache_duration", cacheDuration);
                fields.put("audio_output", property(player, "current-ao"))
                    .put("audio_codec", property(player, "audio-codec"))
                    .put("audio_format", property(player, "audio-out-params/format"));
                Integer rate = player.getPropertyInt("audio-out-params/samplerate");
                Integer channels = player.getPropertyInt("audio-out-params/channel-count");
                if (rate != null) fields.put("audio_rate", rate);
                if (channels != null) fields.put("audio_channels", channels);
                Boolean actualMute = player.getPropertyBoolean("mute"), actualPause = player.getPropertyBoolean("pause");
                Double actualVolume = player.getPropertyDouble("volume");
                if (actualMute != null) fields.put("actual_mute", actualMute);
                if (actualPause != null) fields.put("actual_pause", actualPause);
                if (actualVolume != null && finite(actualVolume)) fields.put("actual_volume", actualVolume);
            }
            if (!target.errorKind.isEmpty()) fields.put("error_kind", target.errorKind).put("error_code", target.errorCode);
            diagnostic = fields.toString();
        } catch (Exception ignored) {}
    }
    private static String property(MPVLib player, String name) {
        String value = player.getPropertyString(name);
        // Only codec/output properties are queried. Bound strings before crossing the bridge.
        return value == null ? "" : value.substring(0, Math.min(value.length(), 80));
    }
    private void event(Session target, String name, JSONObject detail) {
        if (!current(target)) return;
        target.state = name; updateDiagnostic(target, detail); send(target.id, name, detail);
    }
    private void send(String id, String name, JSONObject detail) {
        if (!destroyed) web.evaluateJavascript("window.HongguoNativePlayback&&window.HongguoNativePlayback.event(" + JSONObject.quote(id) + "," + JSONObject.quote(name) + "," + (detail == null ? "{}" : detail.toString()) + ")", null);
    }
    @JavascriptInterface public void begin(String id) { stop(); }
    @JavascriptInterface public void stop() { handler.post(() -> { requestStop(null, null, null); holder.setVisibility(View.VISIBLE); holder.setAlpha(0f); }); }
    @JavascriptInterface public void pause() { handler.post(() -> {
        if (stopFence.isActive()) { transition.setPlayIntent(false); return; }
        Session s = session; if (s != null) guarded(s, () -> { s.wantsPlay = false; if (s.player != null) s.player.setPropertyBoolean("pause", true); });
    }); }
    @JavascriptInterface public void resume() { handler.post(() -> {
        if (stopFence.isActive()) { transition.setPlayIntent(true); return; }
        Session s = session; if (s != null) guarded(s, () -> { s.wantsPlay = true; if (s.player != null && s.output.loaded && s.output.attached && !background) s.player.setPropertyBoolean("pause", false); });
    }); }
    @JavascriptInterface public void setSpeed(double value) {
        if (!finite(value) || value <= 0 || value > 4) return;
        handler.post(() -> { speed = value; Session s = session; if (s != null) guarded(s, () -> { if (s.player != null) s.player.setPropertyDouble("speed", value); }); });
    }
    @JavascriptInterface public void setVolume(double value, boolean silent) {
        if (!finite(value)) return;
        handler.post(() -> { volume = Math.max(0, Math.min(1, value)); muted = silent;
            Session s = session; if (s != null) guarded(s, () -> { if (s.player != null) { s.player.setPropertyDouble("volume", volume * 100); s.player.setPropertyBoolean("mute", muted); } });
        });
    }
    @JavascriptInterface public void setLoudness(boolean enabled) {
        handler.post(() -> { loudness = enabled;
            Session s = session; if (s != null) guarded(s, () -> { if (s.player != null) s.player.setPropertyString("af", enabled ? "dynaudnorm" : ""); });
        });
    }
    @JavascriptInterface public void seekTo(double seconds) {
        if (!finite(seconds) || seconds < 0) return;
        handler.post(() -> { Session s = session; if (s != null) guarded(s, () -> {
            s.position = Math.round(seconds * 1000);
            s.pendingSeek = !s.output.loaded;
            // User initiated seeks should land on a nearby keyframe first.
            // Initial resume/episode positions keep absolute+exact in the
            // FILE_LOADED path, where timestamp fidelity matters more than
            // interactive latency on a network source.
            if (s.output.loaded && s.player != null) s.player.command(new String[]{"seek", Double.toString(seconds), "absolute"});
        }); });
    }
    @JavascriptInterface public void seekBy(int seconds) { handler.post(() -> { if (session != null) seekTo(Math.max(0, session.position / 1000.0 + Math.max(-60, Math.min(60, seconds)))); }); }
    @JavascriptInterface public void bounds(float l, float t, float w, float h) {
        if (!PlaybackBounds.valid(l, t, w, h)) return;
        handler.post(() -> {
            left = l; top = t; width = w; height = h;
            // Do not resize the holder while an old source is fenced. A
            // LayoutParams change can destroy/recreate the SurfaceView, and
            // that is the exact window in which the next source is being
            // attached. installSession() applies the latest cached bounds
            // once the fence has completed and before attachSurface().
            applyBoundsWhenIdle();
        });
    }
    private void applyBoundsWhenIdle() {
        if (!stopFence.isActive()) applyBounds();
    }
    private boolean applyBounds() {
        if (!PlaybackBounds.valid(left, top, width, height)) return false;
        int w = Math.round(web.getWidth() * width), h = Math.round(web.getHeight() * height);
        if (!PlaybackBounds.visible(w, h)) return false;
        FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) holder.getLayoutParams();
        int l = Math.round(web.getWidth() * left), t = Math.round(web.getHeight() * top);
        if (current != null && current.width == w && current.height == h
                && current.leftMargin == l && current.topMargin == t) {
            return true;
        }
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(w, h);
        layout.leftMargin = l; layout.topMargin = t;
        holder.setLayoutParams(layout);
        return true;
    }
    @Override public void surfaceCreated(SurfaceHolder surfaceHolder) {
        surfaceReady = true;
        if (installPendingSessionIfReady()) return;
        Session target = session;
        if (target == null || !current(target)) return;
        armTimeout(target);
        start(target);
    }
    private void surfaceSize(Session target, int w, int h) {
        target.output.surfaceWidth = w; target.output.surfaceHeight = h;
        if (target.player != null && target.output.attached && w > 0 && h > 0)
            target.player.setPropertyString("android-surface-size", w + "x" + h);
    }
    @Override public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int w, int h) {
        surfaceReady = true;
        if (installPendingSessionIfReady()) return;
        Session target = session;
        if (target != null) guarded(target, () -> { surfaceSize(target, w, h); rendered(target); });
    }
    @Override public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        surfaceReady = false;
        surfaceEpoch++;
        Session s = session;
        boolean sessionOwnsPlayer = s != null && s.player != null;
        boolean currentSession = current(s);
        MpvSurfaceLifecyclePolicy.Action action = MpvSurfaceLifecyclePolicy.onDestroyed(
            stopFence.isActive(), sessionOwnsPlayer, currentSession, player != null);
        if (action == MpvSurfaceLifecyclePolicy.Action.DEFER_STOPPING_SESSION) {
            // Let the stop reply and queued END_FILE/events settle before
            // destroying libmpv. finishStopBoundary() will retire it after
            // the fence if this Surface generation is no longer reusable.
            return;
        }
        if (action == MpvSurfaceLifecyclePolicy.Action.RECREATE_ACTIVE_SESSION) guarded(s, () -> {
            Double position = s.player.getPropertyDouble("time-pos");
            if (position != null && finite(position)) s.position = Math.max(0, Math.round(position * 1000));
            // libmpv's Android VO captures ANativeWindow at initialization.
            // Changing wid alone cannot rebind that VO to a recreated surface.
            // Destroy before releasing its global reference, then restore the
            // source/position when SurfaceView creates a new surface.
            dispose(s); s.output.loaded = false; s.outputReady = false;
            s.output.restarted = false; s.output.configured = false; s.output.vo = "";
            s.output.surfaceWidth = 0; s.output.surfaceHeight = 0;
            event(s, "buffering", null);
        });
        else if (action == MpvSurfaceLifecyclePolicy.Action.RETIRE_ORPHAN) {
            MPVLib stale = s != null && s.player != null ? s.player : player;
            if (s != null && s.player == stale) {
                s.player = null;
                s.output.attached = false;
                s.output.loaded = false;
                s.outputReady = false;
                if (session == s && !current(s)) session = null;
            }
            retire(stale);
        }
    }
    private void dispose(Session target) {
        if (target.timeout != null) handler.removeCallbacks(target.timeout);
        cancelProbe(target);
        MPVLib old = target.player; target.player = null;
        if (old != null) {
            try {
                old.setPropertyBoolean("mute", true);
            } catch (RuntimeException ignored) {
                // Destroy still owns cleanup if a dying source rejected a command.
            } finally { target.output.attached = false; if (old == player) player = null; playerSurfaceAttached = false; playerQuiescent = true; old.destroy(); }
        }
    }
    private void requestStop(String id, String source, JSONObject data) {
        if (stopFence.isActive()) {
            if (id == null) { generation++; transition.clear(); }
            else transition.queue(new Session(generation, id, source, data, MpvDecodeMode.stored(decoderMode())));
            return;
        }
        Session old = session; session = null; generation++;
        if (old != null) {
            old.terminal = true;
            if (old.timeout != null) handler.removeCallbacks(old.timeout);
            cancelProbe(old);
            old.player = null;
        }
        if (id == null) transition.clear();
        else transition.queue(new Session(generation, id, source, data, MpvDecodeMode.stored(decoderMode())));
        if (player == null || playerQuiescent) {
            finishStopBoundary(true);
            return;
        }
        stoppingPlayer = player;
        boolean expectEndFile = old != null && old.output.openRequested && !"ended".equals(old.state);
        long requestId = ++stopCommandSequence;
        stopSurfaceEpoch = surfaceEpoch;
        stopFenceStartedAt = SystemClock.uptimeMillis();
        stopFence.begin(requestId, expectEndFile);
        stopTimeout = () -> {
            if (stopFence.onTimeout() && stoppingPlayer == player) finishStopBoundary(false);
        };
        // Slow TV boxes can spend well over three seconds stopping a stalled
        // upstream source. A timed-out player used to be destroyed while its
        // MediaCodec was still releasing asynchronously, which trips the
        // vendor codec's "mReplyID == null" CHECK and kills the process
        // (Fatal signal 6 in MediaCodec_loop on rk356x). Give the graceful
        // stop twice the budget; the fence still settles early when mpv
        // replies, so normal switches never wait this long.
        handler.postDelayed(stopTimeout, 6000);
        try {
            player.setPropertyBoolean("pause", true);
            player.commandAsync(new String[]{"stop"}, requestId);
        } catch (RuntimeException failure) { finishStopBoundary(false); }
    }
    private void retire(MPVLib stale) {
        if (stale == null) return;
        if (stale == player) { player = null; playerSurfaceAttached = false; playerQuiescent = true; }
        try { stale.destroy(); } catch (RuntimeException ignored) {}
    }
    private void finishStopBoundary(boolean reusable) {
        if (stopTimeout != null) handler.removeCallbacks(stopTimeout);
        stopTimeout = null;
        boolean hadFence = stopFence.isActive();
        if (hadFence && stopFenceStartedAt > 0) {
            lastStopFenceMs = Math.max(0, SystemClock.uptimeMillis() - stopFenceStartedAt);
            stopFenceStartedAt = 0;
        }
        stopFence.clear();
        MPVLib stopped = stoppingPlayer; stoppingPlayer = null;
        boolean sameSurface = !hadFence || stopSurfaceEpoch == surfaceEpoch;
        stopSurfaceEpoch = -1;
        boolean canReuse = !hadFence || MpvSurfaceLifecyclePolicy.canReuseStoppedPlayer(
            reusable, sameSurface, surfaceAvailable());
        if (!canReuse) retireAfterStop(stopped);
        else playerQuiescent = true;
        // A settled stop only means mpv delivered END_FILE and drained its
        // events; the vendor decoder's own asynchronous release can still be
        // running. Installing the next source immediately re-initializes a
        // second MediaCodec on top of that release and crashes rk356x boxes
        // (SIGABRT in MediaCodec_loop). One short settle beat lets the old
        // release finish before loadfile starts a new decoder. The copy-mode
        // hwdec path keeps the vendor RGA blitter out of the loop, so a
        // shorter beat stays safe here.
        if (hadFence && !destroyed) handler.postDelayed(this::installPendingSessionIfReady, MPV_POST_STOP_SETTLE_MS);
        else installPendingSessionIfReady();
    }
    private static final long MPV_POST_STOP_SETTLE_MS = 250;
    /** Retires a stopped player after the settle beat, so its MediaCodec release drains first. */
    private void retireAfterStop(MPVLib stale) {
        if (stale == null) return;
        if (stale == player) { player = null; playerSurfaceAttached = false; playerQuiescent = true; }
        if (destroyed) { try { stale.destroy(); } catch (RuntimeException ignored) {} return; }
        handler.postDelayed(() -> { try { stale.destroy(); } catch (RuntimeException ignored) {} }, MPV_POST_STOP_SETTLE_MS);
    }
    private boolean installPendingSessionIfReady() {
        if (stopFence.isActive()) return false;
        Session pending = transition.takePendingSessionWhenReady(surfaceAvailable());
        if (pending == null) return false;
        installSession(pending);
        return true;
    }
    private boolean surfaceAvailable() {
        try { return surfaceReady && surface.getHolder().getSurface().isValid(); }
        catch (RuntimeException ignored) { return false; }
    }
    @Override public void onPause() {
        background = true; Session s = session;
        if (s != null) { if (s.timeout != null) handler.removeCallbacks(s.timeout); guarded(s, () -> { if (s.player != null) s.player.setPropertyBoolean("pause", true); }); }
    }
    @Override public void onResume() {
        background = false; Session s = session;
        if (s != null) guarded(s, () -> { armTimeout(s); start(s); if (s.player != null && s.output.loaded && s.output.attached) s.player.setPropertyBoolean("pause", !s.wantsPlay); });
    }
    @Override public void destroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null);
        Session active = session; session = null; transition.clear(); generation++;
        if (active != null) {
            active.terminal = true;
            if (active.timeout != null) handler.removeCallbacks(active.timeout);
            cancelProbe(active);
        }
        stopFence.clear(); stoppingPlayer = null; stopTimeout = null;
        surface.getHolder().removeCallback(this);
        MPVLib old = player; player = null; playerSurfaceAttached = false;
        surfaceReady = false;
        releaseProbeBitmap();
        if (old != null) { try { old.destroy(); } catch (RuntimeException ignored) {} }
        holder.setAlpha(0f); holder.setVisibility(View.GONE);
    }
}
