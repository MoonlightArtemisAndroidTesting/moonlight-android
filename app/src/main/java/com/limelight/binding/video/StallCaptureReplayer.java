package com.limelight.binding.video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Replays a capture saved by StallVideoCapture through a hardware decoder, configured the way
// MediaCodecDecoderRenderer configures it for a stream, and reports where decoder output stopped.
// This tells whether a stall is triggered by the video data itself (it reproduces at the same
// frame every time) or by conditions only present during a live stream.
public class StallCaptureReplayer {
    // Same thresholds as the output stall watchdog in MediaCodecDecoderRenderer
    private static final int STALL_MIN_FRAMES = 3;
    private static final long STALL_MIN_NS = 50_000_000L;
    // How long to wait for the decoder to drain after end of stream, or to accept input
    private static final long DRAIN_TIMEOUT_MS = 3000;
    private static final long INPUT_TIMEOUT_MS = 3000;
    // In original timing mode, feeding a frame this late is reported, since the resulting burst
    // of input is not what the stream did
    private static final long INPUT_LATE_REPORT_MS = 30;

    public interface Listener {
        void onLog(String line);
    }

    // One frame of the capture: the parameter sets (IDR frames only) and picture decode units,
    // each as {byte offset, length} into the .h265 file
    static class Frame {
        final int frameNumber;
        final boolean idr;
        final long msSinceStart;
        final List<long[]> csd = new ArrayList<>();
        final List<long[]> pictures = new ArrayList<>();

        Frame(int frameNumber, boolean idr, long msSinceStart) {
            this.frameNumber = frameNumber;
            this.idr = idr;
            this.msSinceStart = msSinceStart;
        }
    }

    public static class CaptureInfo {
        public String decoderName;
        public int width, height, fps;
        public boolean tenBit;

        // Parses the stall-<time>.txt written by StallVideoCapture
        public static CaptureInfo parse(File file) throws IOException {
            CaptureInfo info = new CaptureInfo();
            Pattern resolution = Pattern.compile("Resolution: (\\d+)x(\\d+) at (\\d+) FPS");
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Decoder: ")) {
                        info.decoderName = line.substring("Decoder: ".length()).trim();
                    }
                    else if (line.startsWith("Video format: ")) {
                        info.tenBit = line.contains("10-bit");
                    }
                    else {
                        Matcher m = resolution.matcher(line);
                        if (m.find()) {
                            info.width = Integer.parseInt(m.group(1));
                            info.height = Integer.parseInt(m.group(2));
                            info.fps = Integer.parseInt(m.group(3));
                        }
                    }
                }
            }
            if (info.decoderName == null || info.width == 0) {
                throw new IOException("Missing decoder or resolution in "+file.getName());
            }
            return info;
        }
    }

    public static class Result {
        public int framesIn, framesOut;
        public int firstFrame, lastFrameIn, lastFrameOut;
        public boolean drained;
        public String abortReason;
        public final List<String> stalls = new ArrayList<>();
        public final List<String> inputDelays = new ArrayList<>();

        public boolean reproduced() {
            return !drained || !stalls.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(framesIn).append(" frames in, ").append(framesOut).append(" out");
            if (abortReason != null) {
                sb.append(". Aborted: ").append(abortReason);
            }
            if (!drained) {
                sb.append(". STALL REPRODUCED: output stopped after frame ").append(lastFrameOut)
                        .append(" and never resumed (last frame in: ").append(lastFrameIn).append(")");
            }
            else if (!stalls.isEmpty()) {
                sb.append(". Temporary stalls: ").append(String.join("; ", stalls));
            }
            else {
                sb.append(". Not reproduced: every frame decoded");
            }
            if (!inputDelays.isEmpty()) {
                sb.append(". Replay input fell behind (not a decoder problem): ").append(String.join("; ", inputDelays));
            }
            return sb.toString();
        }
    }

    private final File videoFile;
    private final List<Frame> frames;
    private final CaptureInfo info;
    private final Listener listener;
    private volatile boolean cancelled;

    // Position of each frame number in frames
    private final Map<Integer, Integer> frameIndexByNumber = new HashMap<>();

    // Shared between the input (calling) thread and the output thread during a run
    private final Map<Long, Integer> ptsToFrame = new ConcurrentHashMap<>();
    // When each frame (by index) was fully queued, and the index of the last queued frame
    private AtomicLongArray queueNsByIndex;
    private volatile int lastQueuedIndex;
    private volatile boolean eosReceived;

    public StallCaptureReplayer(File videoFile, File indexFile, File infoFile, Listener listener) throws IOException {
        this.videoFile = videoFile;
        this.listener = listener;
        this.info = CaptureInfo.parse(infoFile);
        try (BufferedReader reader = new BufferedReader(new FileReader(indexFile))) {
            this.frames = parseIndex(reader);
        }
        if (frames.isEmpty() || !frames.get(0).idr) {
            throw new IOException("Capture doesn't start with an IDR frame");
        }
        for (int i = 0; i < frames.size(); i++) {
            frameIndexByNumber.put(frames.get(i).frameNumber, i);
        }
    }

    // Reads the whole capture once so storage speed can't affect a replay: a slow first read
    // delays input and then bursts it, which isn't what the stream did. Returns MB/s.
    public double warmUp() throws IOException {
        long startNs = System.nanoTime();
        long total = 0;
        byte[] chunk = new byte[1024 * 1024];
        try (RandomAccessFile video = new RandomAccessFile(videoFile, "r")) {
            int read;
            while (!cancelled && (read = video.read(chunk)) > 0) {
                total += read;
            }
        }
        double seconds = Math.max(1e-6, (System.nanoTime() - startNs) / 1e9);
        return total / (1024.0 * 1024.0) / seconds;
    }

    public CaptureInfo getInfo() {
        return info;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public void cancel() {
        cancelled = true;
    }

    // Parses the stall-<time>.csv index. Lines starting with '#' are stall notes and are skipped.
    static List<Frame> parseIndex(BufferedReader reader) throws IOException {
        List<Frame> frames = new ArrayList<>();
        Frame current = null;
        String line = reader.readLine(); // Header
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // frame_number,frame_type,buffer_type,byte_offset,length,ms_since_segment_start
            String[] f = line.split(",");
            if (f.length < 6) {
                throw new IOException("Malformed index line: "+line);
            }
            int frameNumber = Integer.parseInt(f[0]);
            if (current == null || current.frameNumber != frameNumber) {
                current = new Frame(frameNumber, f[1].equals("IDR"), Long.parseLong(f[5]));
                frames.add(current);
            }

            long[] unit = {Long.parseLong(f[3]), Long.parseLong(f[4])};
            if (f[2].equals("PIC")) {
                current.pictures.add(unit);
            }
            else {
                current.csd.add(unit);
            }
        }
        return frames;
    }

    // Runs one replay on the calling thread. realTime replays each frame at the time it was
    // originally submitted; otherwise frames are fed as fast as the decoder accepts them.
    public Result run(Surface surface, boolean realTime, boolean ultraLowLatency) throws IOException {
        Result result = new Result();
        result.firstFrame = frames.get(0).frameNumber;
        ptsToFrame.clear();
        queueNsByIndex = new AtomicLongArray(frames.size());
        lastQueuedIndex = -1;
        eosReceived = false;

        MediaCodec codec = createDecoder(surface, ultraLowLatency);
        OutputThread output = new OutputThread(codec, result);
        output.start();

        try (RandomAccessFile video = new RandomAccessFile(videoFile, "r")) {
            byte[] buffer = new byte[0];
            long startNs = System.nanoTime();
            long lastPtsUs = -1;
            boolean reportedLate = false;

            for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
                Frame frame = frames.get(frameIndex);
                if (cancelled) {
                    result.abortReason = "cancelled";
                    break;
                }

                if (realTime) {
                    // Sleep in short steps so a gap in the capture doesn't delay cancellation
                    long dueNs = startNs + frame.msSinceStart * 1_000_000L;
                    long waitMs;
                    while (!cancelled && (waitMs = (dueNs - System.nanoTime()) / 1_000_000L) > 0) {
                        try {
                            Thread.sleep(Math.min(waitMs, 50));
                        } catch (InterruptedException e) {
                            cancelled = true;
                        }
                    }
                    if (cancelled) {
                        result.abortReason = "cancelled";
                        break;
                    }
                }

                // Mirrors MediaCodecDecoderRenderer: the parameter sets go in one codec config
                // buffer ahead of the IDR picture
                if (frame.idr && !frame.csd.isEmpty()) {
                    buffer = readUnits(video, frame.csd, buffer);
                    if (!queue(codec, buffer, totalLength(frame.csd), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, result, frame)) {
                        break;
                    }
                }

                boolean failed = false;
                for (long[] picture : frame.pictures) {
                    List<long[]> one = new ArrayList<>();
                    one.add(picture);
                    buffer = readUnits(video, one, buffer);

                    // Timestamps must be unique, as in MediaCodecDecoderRenderer
                    long ptsUs = Math.max(frame.msSinceStart * 1000, lastPtsUs + 1);
                    lastPtsUs = ptsUs;
                    ptsToFrame.put(ptsUs, frame.frameNumber);

                    if (!queue(codec, buffer, (int) picture[1], ptsUs,
                            frame.idr ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0, result, frame)) {
                        failed = true;
                        break;
                    }
                }
                if (failed) {
                    break;
                }

                long queuedNs = System.nanoTime();
                queueNsByIndex.set(frameIndex, queuedNs);
                lastQueuedIndex = frameIndex;
                result.framesIn++;
                result.lastFrameIn = frame.frameNumber;

                if (realTime) {
                    // Report each episode of falling behind schedule once
                    long lateMs = (queuedNs - startNs) / 1_000_000L - frame.msSinceStart;
                    if (lateMs >= INPUT_LATE_REPORT_MS && !reportedLate) {
                        result.inputDelays.add(lateMs+" ms late at frame "+frame.frameNumber);
                        reportedLate = true;
                    }
                    else if (lateMs < INPUT_LATE_REPORT_MS / 2) {
                        reportedLate = false;
                    }
                }
            }

            if (result.abortReason == null) {
                // Signal end of stream and wait for the decoder to output everything
                int index = dequeueInput(codec);
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
                    while (!eosReceived && !cancelled && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }
        } finally {
            output.finish();
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // A stuck decoder may refuse to stop cleanly, release it regardless
            }
            codec.release();
        }

        result.drained = eosReceived && result.abortReason == null;
        return result;
    }

    private boolean queue(MediaCodec codec, byte[] data, int length, long ptsUs, int flags, Result result, Frame frame) {
        int index = dequeueInput(codec);
        if (index < 0) {
            result.abortReason = "decoder stopped accepting input at frame "+frame.frameNumber;
            return false;
        }

        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        if (inputBuffer == null || inputBuffer.capacity() < length) {
            result.abortReason = "decode unit of frame "+frame.frameNumber+" doesn't fit the input buffer";
            return false;
        }
        inputBuffer.clear();
        inputBuffer.put(data, 0, length);
        codec.queueInputBuffer(index, 0, length, ptsUs, flags);
        return true;
    }

    private int dequeueInput(MediaCodec codec) {
        long deadline = System.currentTimeMillis() + INPUT_TIMEOUT_MS;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static int totalLength(List<long[]> units) {
        int length = 0;
        for (long[] unit : units) {
            length += (int) unit[1];
        }
        return length;
    }

    private static byte[] readUnits(RandomAccessFile video, List<long[]> units, byte[] buffer) throws IOException {
        int length = totalLength(units);
        if (buffer.length < length) {
            buffer = new byte[length];
        }
        int position = 0;
        for (long[] unit : units) {
            video.seek(unit[0]);
            video.readFully(buffer, position, (int) unit[1]);
            position += (int) unit[1];
        }
        return buffer;
    }

    // Mirrors MediaCodecDecoderRenderer.createBaseMediaFormat() and its configuration retry loop
    private MediaCodec createDecoder(Surface surface, boolean ultraLowLatency) throws IOException {
        MediaCodecInfo decoderInfo = findDecoder(info.decoderName);
        if (decoderInfo == null) {
            throw new IOException("Decoder "+info.decoderName+" not found on this device");
        }

        String mimeType = "video/hevc";
        for (int tryNumber = 0;; tryNumber++) {
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, info.width, info.height);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_FRAME_RATE, info.fps);
            }
            if (MediaCodecHelper.decoderSupportsAdaptivePlayback(decoderInfo, mimeType)) {
                format.setInteger(MediaFormat.KEY_MAX_WIDTH, info.width);
                format.setInteger(MediaFormat.KEY_MAX_HEIGHT, info.height);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                if (!info.tenBit) {
                    format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                    format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                }
            }
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(format, decoderInfo, ultraLowLatency, tryNumber);

            MediaCodec codec = null;
            try {
                codec = MediaCodec.createByCodecName(decoderInfo.getName());
                codec.configure(format, surface, null, 0);
                codec.start();
                listener.onLog("Configured "+decoderInfo.getName()+" with "+format);
                return codec;
            } catch (IllegalArgumentException | IllegalStateException e) {
                if (codec != null) {
                    codec.release();
                }
                if (!newFormat) {
                    throw new IOException("Unable to configure "+decoderInfo.getName(), e);
                }
            }
        }
    }

    private static MediaCodecInfo findDecoder(String name) {
        for (MediaCodecInfo codecInfo : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!codecInfo.isEncoder() && codecInfo.getName().equalsIgnoreCase(name)) {
                return codecInfo;
            }
        }
        return null;
    }

    // Drains decoder output, renders it to the surface as the stream does, and records stalls
    private class OutputThread extends Thread {
        private final MediaCodec codec;
        private final Result result;
        private volatile boolean finished;

        OutputThread(MediaCodec codec, Result result) {
            super("Stall replay output");
            this.codec = codec;
            this.result = result;
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int lastOutputIndex = -1;
            while (!finished && !eosReceived) {
                int index;
                try {
                    index = codec.dequeueOutputBuffer(bufferInfo, 5000);
                } catch (IllegalStateException e) {
                    return;
                }
                if (index < 0) {
                    continue;
                }

                boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                Integer frameNumber = ptsToFrame.get(bufferInfo.presentationTimeUs);
                Integer frameIndex = frameNumber != null ? frameIndexByNumber.get(frameNumber) : null;
                if (frameIndex != null) {
                    // Same rule as the output stall watchdog: measure from when the decoder was
                    // given the oldest frame it hadn't answered, not from the last output, so a
                    // burst of input after a gap isn't mistaken for a stall. Like the watchdog,
                    // ignore startup before the first output.
                    int oldestUnanswered = lastOutputIndex + 1;
                    int queued = lastQueuedIndex - lastOutputIndex;
                    if (lastOutputIndex >= 0 && oldestUnanswered <= lastQueuedIndex && queued >= STALL_MIN_FRAMES) {
                        long waitNs = System.nanoTime() - queueNsByIndex.get(oldestUnanswered);
                        if (waitNs >= STALL_MIN_NS) {
                            result.stalls.add("no output for "+waitNs / 1_000_000L+" ms after frame "+
                                    result.lastFrameOut+" with "+queued+" frames queued");
                        }
                    }
                    lastOutputIndex = Math.max(lastOutputIndex, frameIndex);
                    result.framesOut++;
                    result.lastFrameOut = frameNumber;
                }

                codec.releaseOutputBuffer(index, bufferInfo.size > 0);
                if (eos) {
                    eosReceived = true;
                }
            }
        }

        void finish() {
            finished = true;
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
