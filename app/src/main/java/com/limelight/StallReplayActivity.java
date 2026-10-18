package com.limelight;

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
public class StallReplayActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final int[] REPEAT_COUNTS = {1, 3, 5, 10};

    private File captureDir;
    private final List<File> captures = new ArrayList<>();

    private Spinner captureSpinner, modeSpinner, repeatSpinner;
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
        repeatSpinner = findViewById(R.id.stallReplayRepeat);
        startButton = findViewById(R.id.stallReplayStart);
        logView = findViewById(R.id.stallReplayLog);
        logScroll = findViewById(R.id.stallReplayLogScroll);
        SurfaceView surfaceView = findViewById(R.id.stallReplaySurface);
        surfaceView.getHolder().addCallback(this);

        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[] {getString(R.string.stall_replay_mode_realtime), getString(R.string.stall_replay_mode_fast)}));
        String[] repeatNames = new String[REPEAT_COUNTS.length];
        for (int i = 0; i < REPEAT_COUNTS.length; i++) {
            repeatNames[i] = Integer.toString(REPEAT_COUNTS[i]);
        }
        repeatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, repeatNames));

        File baseDir = getExternalFilesDir(null);
        captureDir = new File(baseDir != null ? baseDir : getFilesDir(), "stall-captures");
        loadCaptures();

        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> {
            if (replayThread != null) {
                stopReplay();
            }
            else {
                startReplay();
            }
        });
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
        boolean realTime = modeSpinner.getSelectedItemPosition() == 0;
        int repeats = REPEAT_COUNTS[Math.max(0, repeatSpinner.getSelectedItemPosition())];
        boolean ultraLowLatency = PreferenceConfiguration.readPreferences(this).enableUltraLowLatency;

        stopRequested = false;
        startButton.setText(R.string.stall_replay_stop);
        replayThread = new Thread(() -> {
            int reproduced = 0, completed = 0;
            try {
                StallCaptureReplayer replayer = new StallCaptureReplayer(video, index, info, this::log);
                log("");
                log("Replaying "+base+": "+replayer.getFrameCount()+" frames, "+
                        (realTime ? "original timing" : "as fast as possible")+", "+repeats+" run(s), "+
                        "ultra low latency "+(ultraLowLatency ? "on" : "off"));

                log("Reading the capture into memory first...");
                log(String.format(Locale.US, "Read at %.0f MB/s", replayer.warmUp()));

                for (int run = 1; run <= repeats && !stopRequested; run++) {
                    activeReplayer = replayer;
                    StallCaptureReplayer.Result result = replayer.run(surface, realTime, ultraLowLatency);
                    activeReplayer = null;
                    if (result.abortReason != null && result.abortReason.equals("cancelled")) {
                        break;
                    }

                    completed++;
                    if (result.reproduced()) {
                        reproduced++;
                    }
                    String line = "Run "+run+": "+result;
                    log(line);
                    appendResult(base, realTime, line);
                }

                String summary = "Summary for "+base+": stall reproduced in "+reproduced+" of "+completed+" run(s)";
                log(summary);
                appendResult(base, realTime, summary);
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

    private void appendResult(String base, boolean realTime, String line) {
        File results = new File(captureDir, "replay-results.txt");
        try (PrintWriter out = new PrintWriter(new FileOutputStream(results, true))) {
            out.println(new Date()+" "+base+" ["+(realTime ? "original timing" : "fast")+"] "+line);
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
