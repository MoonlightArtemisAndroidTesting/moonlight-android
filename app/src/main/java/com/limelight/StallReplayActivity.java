package com.limelight;

import android.content.Intent;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.binding.video.StallCaptureReplayer;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Testing tool: replays captures saved by "Capture video on decoder stall" through the same
// hardware decoder, to check whether a stall reproduces from the video data alone. Lists the
// captures in the app's stall-captures folder; others can be added there with adb push.
//
// It can also be started over ADB, so soaks can be run unattended:
//   adb shell am start -n com.limelight.noirdebug/com.limelight.StallReplayActivity \
//       --ez autoStart true --ei runs 165 --ez realTime true --ez appStyleOutput true \
//       --ei vdecLowLatency 1 --es capture stall-20261017-201946-746
// Decoder setting extras (androidLowLatency, vdecLowLatency, vendorLowLatency,
// realtimePriority) take 0 = app default, 1 = off, 2 = on. Any extra left out keeps the
// screen's default. Results go to replay-results.txt as usual.
public class StallReplayActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    // The longer counts are for soak tests: about 165 runs of a 44 second capture is 2 hours
    private static final int[] REPEAT_COUNTS = {1, 3, 5, 10, 50, 100, 200};

    // Launch extras, see the class comment
    private static final String EXTRA_AUTO_START = "autoStart";
    private static final String EXTRA_CAPTURE = "capture";
    private static final String EXTRA_RUNS = "runs";
    private static final String EXTRA_REAL_TIME = "realTime";
    private static final String EXTRA_APP_STYLE_OUTPUT = "appStyleOutput";
    private static final String EXTRA_ANDROID_LOW_LATENCY = "androidLowLatency";
    private static final String EXTRA_VDEC_LOW_LATENCY = "vdecLowLatency";
    private static final String EXTRA_VENDOR_LOW_LATENCY = "vendorLowLatency";
    private static final String EXTRA_REALTIME_PRIORITY = "realtimePriority";

    // Set from the launch extras: a run count not offered by the spinner, and whether to start
    // as soon as the surface is ready
    private int runsOverride;
    private boolean pendingAutoStart;

    private File captureDir;
    private final List<File> captures = new ArrayList<>();

    private Spinner captureSpinner, modeSpinner, outputSpinner, repeatSpinner;
    // Decoder setting overrides. Positions match StallCaptureReplayer.SETTING_* (default, off, on).
    private Spinner androidLowLatencySpinner, vdecLowLatencySpinner, vendorLowLatencySpinner, realtimePrioritySpinner;
    private Button startButton;
    private TextView logView;
    private ScrollView logScroll;

    private volatile Surface surface;
    private Thread replayThread;
    private volatile StallCaptureReplayer activeReplayer;
    private volatile boolean stopRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stall_replay);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // The decoder low latency options depend on device detection done here
        MediaCodecHelper.initialize(this, "");

        captureSpinner = findViewById(R.id.stallReplayCapture);
        modeSpinner = findViewById(R.id.stallReplayMode);
        outputSpinner = findViewById(R.id.stallReplayOutput);
        repeatSpinner = findViewById(R.id.stallReplayRepeat);
        startButton = findViewById(R.id.stallReplayStart);
        logView = findViewById(R.id.stallReplayLog);
        logScroll = findViewById(R.id.stallReplayLogScroll);
        SurfaceView surfaceView = findViewById(R.id.stallReplaySurface);
        surfaceView.getHolder().addCallback(this);

        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {getString(R.string.stall_replay_mode_realtime), getString(R.string.stall_replay_mode_fast)}));
        outputSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {getString(R.string.stall_replay_output_all), getString(R.string.stall_replay_output_app)}));
        String[] repeatNames = new String[REPEAT_COUNTS.length];
        for (int i = 0; i < REPEAT_COUNTS.length; i++) {
            repeatNames[i] = Integer.toString(REPEAT_COUNTS[i]);
        }
        repeatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, repeatNames));

        // Stream-style output is the closest match to a real stream, so start there
        outputSpinner.setSelection(1);

        String[] settingNames = {getString(R.string.stall_replay_setting_default),
                getString(R.string.stall_replay_setting_off), getString(R.string.stall_replay_setting_on)};
        androidLowLatencySpinner = findViewById(R.id.stallReplayAndroidLowLatency);
        vdecLowLatencySpinner = findViewById(R.id.stallReplayVdecLowLatency);
        vendorLowLatencySpinner = findViewById(R.id.stallReplayVendorLowLatency);
        realtimePrioritySpinner = findViewById(R.id.stallReplayRealtimePriority);
        for (Spinner spinner : new Spinner[] {androidLowLatencySpinner, vdecLowLatencySpinner,
                vendorLowLatencySpinner, realtimePrioritySpinner}) {
            spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, settingNames));
        }

        File baseDir = getExternalFilesDir(null);
        captureDir = new File(baseDir != null ? baseDir : getFilesDir(), "stall-captures");
        loadCaptures();

        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> {
            if (replayThread != null) {
                stopReplay();
            }
            else {
                runsOverride = 0;
                startReplay();
            }
        });

        applyLaunchOptions(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (replayThread != null) {
            log("Ignoring new launch request: a replay is already running");
            return;
        }
        applyLaunchOptions(intent);
        if (pendingAutoStart && surface != null) {
            pendingAutoStart = false;
            startReplay();
        }
    }

    // Applies ADB launch extras to the screen's controls, so the run is logged exactly as if
    // they had been chosen by hand
    private void applyLaunchOptions(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return;
        }

        String capture = intent.getStringExtra(EXTRA_CAPTURE);
        if (capture != null) {
            int index = -1;
            for (int i = 0; i < captures.size(); i++) {
                if (captures.get(i).getName().equals(capture+".h265")) {
                    index = i;
                }
            }
            if (index < 0) {
                log("Launch option: capture "+capture+" not found, not starting");
                return;
            }
            captureSpinner.setSelection(index);
        }

        if (intent.hasExtra(EXTRA_REAL_TIME)) {
            modeSpinner.setSelection(intent.getBooleanExtra(EXTRA_REAL_TIME, true) ? 0 : 1);
        }
        if (intent.hasExtra(EXTRA_APP_STYLE_OUTPUT)) {
            outputSpinner.setSelection(intent.getBooleanExtra(EXTRA_APP_STYLE_OUTPUT, true) ? 1 : 0);
        }
        applySettingExtra(intent, EXTRA_ANDROID_LOW_LATENCY, androidLowLatencySpinner);
        applySettingExtra(intent, EXTRA_VDEC_LOW_LATENCY, vdecLowLatencySpinner);
        applySettingExtra(intent, EXTRA_VENDOR_LOW_LATENCY, vendorLowLatencySpinner);
        applySettingExtra(intent, EXTRA_REALTIME_PRIORITY, realtimePrioritySpinner);
        runsOverride = Math.max(0, intent.getIntExtra(EXTRA_RUNS, 0));
        pendingAutoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false);
        if (pendingAutoStart) {
            log("Started over ADB, the replay will begin once the screen is ready");
        }
    }

    private static void applySettingExtra(Intent intent, String extra, Spinner spinner) {
        if (intent.hasExtra(extra)) {
            int value = intent.getIntExtra(extra, StallCaptureReplayer.SETTING_APP_DEFAULT);
            if (value >= StallCaptureReplayer.SETTING_APP_DEFAULT && value <= StallCaptureReplayer.SETTING_ON) {
                spinner.setSelection(value);
            }
        }
    }

    private void loadCaptures() {
        captures.clear();
        List<String> names = new ArrayList<>();
        File[] videos = captureDir.listFiles((d, name) -> name.startsWith("stall-") && name.endsWith(".h265"));
        if (videos != null) {
            Arrays.sort(videos);
            for (File video : videos) {
                String base = video.getName().replace(".h265", "");
                if (new File(captureDir, base+".csv").exists() && new File(captureDir, base+".txt").exists()) {
                    captures.add(video);
                    names.add(base+" ("+video.length() / (1024 * 1024)+" MB)");
                }
            }
        }
        captureSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));

        if (captures.isEmpty()) {
            log(getString(R.string.stall_replay_no_captures, captureDir.getAbsolutePath()));
        }
        else {
            log("Captures folder: "+captureDir.getAbsolutePath());
        }
    }

    private void startReplay() {
        int selected = captureSpinner.getSelectedItemPosition();
        if (selected < 0 || selected >= captures.size() || surface == null) {
            return;
        }

        File video = captures.get(selected);
        String base = video.getName().replace(".h265", "");
        File index = new File(captureDir, base+".csv");
        File info = new File(captureDir, base+".txt");
        int repeats = runsOverride > 0 ? runsOverride :
                REPEAT_COUNTS[Math.max(0, repeatSpinner.getSelectedItemPosition())];
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);

        StallCaptureReplayer.Options options = new StallCaptureReplayer.Options();
        options.realTime = modeSpinner.getSelectedItemPosition() == 0;
        options.ultraLowLatency = prefs.enableUltraLowLatency;
        options.appStyleOutput = outputSpinner.getSelectedItemPosition() == 1;
        options.preferLowerDelays = prefs.preferLowerDelays;
        options.refreshRate = getWindowManager().getDefaultDisplay().getRefreshRate();
        options.androidLowLatency = androidLowLatencySpinner.getSelectedItemPosition();
        options.vdecLowLatency = vdecLowLatencySpinner.getSelectedItemPosition();
        options.vendorLowLatency = vendorLowLatencySpinner.getSelectedItemPosition();
        options.realtimePriority = realtimePrioritySpinner.getSelectedItemPosition();
        boolean realTime = options.realTime;

        stopRequested = false;
        startButton.setText(R.string.stall_replay_stop);
        replayThread = new Thread(() -> {
            int permanent = 0, temporary = 0, skipped = 0, completed = 0;
            try {
                StallCaptureReplayer replayer = new StallCaptureReplayer(video, index, info, this::log);
                log("");
                log("Replaying "+base+": "+replayer.getFrameCount()+" frames, "+
                        (realTime ? "original timing" : "as fast as possible")+", "+repeats+" run(s), "+
                        "ultra low latency "+(options.ultraLowLatency ? "on" : "off"));
                log(options.appStyleOutput ?
                        String.format(Locale.US, "Output like the stream: prefer lower delays %s, display %.2f Hz",
                                options.preferLowerDelays ? "on" : "off", options.refreshRate) :
                        "Output: render every frame");
                log("Decoder settings: "+options.describeOverrides());

                log("Reading the capture into memory first...");
                log(String.format(Locale.US, "Read at %.0f MB/s", replayer.warmUp()));

                long soakStartMs = System.currentTimeMillis();
                long framesDecoded = 0;
                for (int run = 1; run <= repeats && !stopRequested; run++) {
                    activeReplayer = replayer;
                    StallCaptureReplayer.Result result = replayer.run(surface, options);
                    activeReplayer = null;
                    if (result.abortReason != null && result.abortReason.equals("cancelled")) {
                        break;
                    }

                    completed++;
                    framesDecoded += result.framesOut;
                    if (result.permanentStall()) {
                        permanent++;
                    }
                    else if (result.missingFrames() > 0) {
                        skipped++;
                    }
                    if (!result.stalls.isEmpty()) {
                        temporary++;
                    }
                    String line = "Run "+run+": "+result;
                    log(line);
                    appendResult(base, options, line);
                }

                // Express the permanent stall rate per hour of decoded video, so it can be
                // compared with the live rate and across timing modes that decode at different speeds
                int fps = Math.max(1, replayer.getInfo().fps);
                double videoHours = framesDecoded / (double) fps / 3600.0;
                String summary = String.format(Locale.US,
                        "Summary for %s: %d permanent stall(s), %d run(s) with temporary stalls, %d with skipped frames, " +
                                "in %d run(s). %d frames decoded (%.2f hours of video at %d FPS) over %.1f minutes. " +
                                "Permanent stalls per hour of video: %.2f. Decoder settings: %s",
                        base, permanent, temporary, skipped, completed, framesDecoded, videoHours, fps,
                        (System.currentTimeMillis() - soakStartMs) / 60000.0,
                        videoHours > 0 ? permanent / videoHours : 0.0, options.describeOverrides());
                log(summary);
                appendResult(base, options, summary);
            } catch (IOException | RuntimeException e) {
                log("Replay failed: "+e);
            } finally {
                runOnUiThread(() -> {
                    replayThread = null;
                    startButton.setText(R.string.stall_replay_start);
                });
            }
        }, "Stall replay");
        replayThread.start();
    }

    private void stopReplay() {
        stopRequested = true;
        StallCaptureReplayer replayer = activeReplayer;
        if (replayer != null) {
            replayer.cancel();
        }
    }

    private void appendResult(String base, StallCaptureReplayer.Options options, String line) {
        File results = new File(captureDir, "replay-results.txt");
        try (PrintWriter out = new PrintWriter(new FileOutputStream(results, true))) {
            out.println(new Date()+" "+base+" ["+(options.realTime ? "original timing" : "fast")+", "+
                    (options.appStyleOutput ? "stream-style output" : "render all")+"] "+line);
        } catch (IOException ignored) {}
    }

    private void log(String line) {
        runOnUiThread(() -> {
            logView.append(line+"\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surface = holder.getSurface();
        startButton.setEnabled(!captures.isEmpty());

        if (pendingAutoStart && replayThread == null) {
            pendingAutoStart = false;
            startReplay();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        // The decoder renders to this surface, so a running replay must end now
        stopReplay();
        Thread thread = replayThread;
        if (thread != null) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        surface = null;
        startButton.setEnabled(false);
    }
}
