package com.limelight.binding.video;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

// Records the HEVC elementary stream fed to the decoder, so the frames leading up to an output
// stall can be replayed offline (ffmpeg, or the same decoder via MediaCodec).
//
// A stream is only decodable from an IDR frame, so the recording is split into segments that each
// start at an IDR frame. A segment is deleted when the next IDR frame arrives, unless a stall was
// detected in it, in which case it is kept as:
//   stall-<time>.h265  Annex B elementary stream, playable with "ffplay stall-<time>.h265"
//   stall-<time>.csv   one row per decode unit with its byte offset, plus "#" lines marking stalls
//   stall-<time>.txt   stream and decoder details
//
// All file I/O happens on a background thread so a slow disk can't stall decoder input.
class StallVideoCapture {
    // Don't let a slow disk eat unbounded memory. If the writer falls this far behind, the
    // current segment is abandoned until the next IDR frame.
    private static final long MAX_QUEUED_BYTES = 64L * 1024 * 1024;
    // With RFI off, IDR frames can be minutes apart. Past this size, the segment is abandoned
    // until the next IDR frame rather than filling the device's storage.
    private static final long MAX_SEGMENT_BYTES = 1024L * 1024 * 1024;
    // Oldest saved captures are deleted once they total more than this. The newest is always kept.
    private static final long MAX_SAVED_BYTES = 2048L * 1024 * 1024;

    private static final int ITEM_DATA = 0;
    private static final int ITEM_NEW_SEGMENT = 1;
    private static final int ITEM_STALL = 2;
    private static final int ITEM_ABANDON = 3;
    private static final int ITEM_CLOSE = 4;

    private static class Item {
        final int type;
        final byte[] data;
        final int frameNumber;
        final int frameType;
        final int bufferType;
        final long timeMs;
        final String note;

        Item(int type, byte[] data, int frameNumber, int frameType, int bufferType, String note) {
            this.type = type;
            this.data = data;
            this.frameNumber = frameNumber;
            this.frameType = frameType;
            this.bufferType = bufferType;
            this.timeMs = System.currentTimeMillis();
            this.note = note;
        }
    }

    private final File dir;
    private final String streamDescription;
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final Thread writerThread;

    // Input thread only
    private int lastFrameNumber;
    private boolean inputDropping;

    // Writer thread writes, perf overlay reads
    private volatile int savedCaptureCount;

    // Writer thread only
    private File segmentVideoFile, segmentIndexFile;
    private OutputStream segmentVideo;
    private PrintWriter segmentIndex;
    private long segmentBytes;
    private long segmentStartMs;
    private boolean segmentAbandoned;
    private boolean segmentHasStall;
    private StringBuilder segmentStallNotes;

    StallVideoCapture(File dir, String streamDescription) {
        this.dir = dir;
        this.streamDescription = streamDescription;

        if (!dir.isDirectory() && !dir.mkdirs()) {
            LimeLog.warning("Stall capture: unable to create "+dir);
        }
        LimeLog.info("Stall capture: recording to "+dir);

        writerThread = new Thread(this::writerLoop, "Video - Stall capture");
        writerThread.setPriority(Thread.MIN_PRIORITY);
        writerThread.start();
    }

    // Input thread only. Call with every decode unit, in submission order.
    void onDecodeUnit(byte[] data, int length, int frameNumber, int frameType, int bufferType) {
        boolean newFrame = frameNumber != lastFrameNumber;
        lastFrameNumber = frameNumber;

        if (newFrame && frameType == MoonBridge.FRAME_TYPE_IDR) {
            // Every IDR frame starts a new segment and gives the writer a chance to catch up
            inputDropping = false;
            queue.add(new Item(ITEM_NEW_SEGMENT, null, frameNumber, frameType, bufferType, null));
        }

        if (inputDropping) {
            return;
        }
        if (queuedBytes.get() + length > MAX_QUEUED_BYTES) {
            // The writer can't keep up. The segment is useless with a hole in it, so give up on
            // it until the next IDR frame.
            inputDropping = true;
            queue.add(new Item(ITEM_ABANDON, null, frameNumber, frameType, bufferType,
                    "writer fell behind"));
            return;
        }

        queuedBytes.addAndGet(length);
        queue.add(new Item(ITEM_DATA, Arrays.copyOf(data, length), frameNumber, frameType, bufferType, null));
    }

    // Input thread only. Marks the current segment to be kept.
    void onStallDetected(int frameNumber, String note) {
        queue.add(new Item(ITEM_STALL, null, frameNumber, 0, 0, note));
    }

    int getSavedCaptureCount() {
        return savedCaptureCount;
    }

    // Saves the current segment if it contains a stall, then stops the writer.
    void close() {
        queue.add(new Item(ITEM_CLOSE, null, 0, 0, 0, null));
        try {
            writerThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writerLoop() {
        try {
            for (;;) {
                Item item = queue.take();
                switch (item.type) {
                    case ITEM_DATA:
                        queuedBytes.addAndGet(-item.data.length);
                        writeData(item);
                        break;
                    case ITEM_NEW_SEGMENT:
                        finishSegment();
                        startSegment(item);
                        break;
                    case ITEM_STALL:
                        markStall(item);
                        break;
                    case ITEM_ABANDON:
                        abandonSegment(item);
                        break;
                    case ITEM_CLOSE:
                        finishSegment();
                        return;
                }
            }
        } catch (InterruptedException e) {
            finishSegment();
        }
    }

    private void startSegment(Item item) {
        segmentBytes = 0;
        segmentStartMs = item.timeMs;
        segmentAbandoned = false;
        segmentHasStall = false;
        segmentStallNotes = new StringBuilder();
        segmentVideoFile = new File(dir, "segment.h265");
        segmentIndexFile = new File(dir, "segment.csv");

        try {
            segmentVideo = new BufferedOutputStream(new FileOutputStream(segmentVideoFile), 1024 * 1024);
            segmentIndex = new PrintWriter(new BufferedOutputStream(new FileOutputStream(segmentIndexFile), 64 * 1024));
            segmentIndex.println("frame_number,frame_type,buffer_type,byte_offset,length,ms_since_segment_start");
        } catch (IOException e) {
            abandonSegment(new Item(ITEM_ABANDON, null, item.frameNumber, 0, 0,
                    "unable to open segment: "+e));
        }
    }

    private void writeData(Item item) {
        if (segmentVideo == null || segmentAbandoned) {
            // Data before the first IDR frame, or a segment we've given up on
            return;
        }

        if (segmentBytes + item.data.length > MAX_SEGMENT_BYTES) {
            abandonSegment(new Item(ITEM_ABANDON, null, item.frameNumber, 0, 0,
                    "segment exceeded "+MAX_SEGMENT_BYTES / (1024 * 1024)+" MB without an IDR frame"));
            return;
        }

        try {
            segmentVideo.write(item.data);
            segmentIndex.println(item.frameNumber+","+frameTypeName(item.frameType)+","+
                    bufferTypeName(item.bufferType)+","+segmentBytes+","+item.data.length+","+
                    (item.timeMs - segmentStartMs));
            segmentBytes += item.data.length;
        } catch (IOException e) {
            abandonSegment(new Item(ITEM_ABANDON, null, item.frameNumber, 0, 0, "write failed: "+e));
        }
    }

    private void markStall(Item item) {
        String line = noteLine(item);
        if (segmentIndex != null && !segmentAbandoned) {
            segmentIndex.println(line);
        }
        if (segmentStallNotes != null) {
            segmentStallNotes.append(line).append('\n');
        }

        segmentHasStall = true;
        if (segmentAbandoned) {
            writeLostCaptureNote();
        }
    }

    // Gives up on the current segment until the next IDR frame
    private void abandonSegment(Item item) {
        if (segmentAbandoned || segmentVideoFile == null) {
            return;
        }

        LimeLog.warning("Stall capture: abandoning segment: "+item.note);
        segmentStallNotes.append(noteLine(item)).append(" (capture abandoned)\n");
        if (segmentHasStall) {
            // We're losing a segment that contained a stall, so at least record that
            writeLostCaptureNote();
        }

        segmentAbandoned = true;
        closeSegmentFiles();
        deleteSegmentFiles();
    }

    private String noteLine(Item item) {
        return "# frame "+item.frameNumber+" at "+(item.timeMs - segmentStartMs)+" ms: "+item.note;
    }

    private void finishSegment() {
        if (segmentVideoFile == null) {
            return;
        }

        closeSegmentFiles();

        if (segmentHasStall && !segmentAbandoned) {
            // Milliseconds keep back-to-back captures (a stall right after a recovery) apart
            String base = "stall-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date(segmentStartMs));
            File video = new File(dir, base+".h265");
            File index = new File(dir, base+".csv");
            if (segmentVideoFile.renameTo(video) && segmentIndexFile.renameTo(index)) {
                writeInfoFile(new File(dir, base+".txt"));
                savedCaptureCount++;
                LimeLog.warning("Stall capture: saved "+video+" ("+segmentBytes+" bytes)");
                pruneOldCaptures();
            }
            else {
                LimeLog.warning("Stall capture: unable to save "+base);
                deleteSegmentFiles();
            }
        }
        else {
            deleteSegmentFiles();
        }

        segmentVideoFile = null;
        segmentIndexFile = null;
    }

    private void closeSegmentFiles() {
        try {
            if (segmentVideo != null) {
                segmentVideo.close();
            }
        } catch (IOException ignored) {}
        if (segmentIndex != null) {
            segmentIndex.close();
        }
        segmentVideo = null;
        segmentIndex = null;
    }

    private void deleteSegmentFiles() {
        if (segmentVideoFile != null) {
            //noinspection ResultOfMethodCallIgnored
            segmentVideoFile.delete();
        }
        if (segmentIndexFile != null) {
            //noinspection ResultOfMethodCallIgnored
            segmentIndexFile.delete();
        }
    }

    private void writeInfoFile(File file) {
        try (PrintWriter out = new PrintWriter(new FileOutputStream(file))) {
            out.println("Segment start: "+new Date(segmentStartMs));
            out.println("Segment size: "+segmentBytes+" bytes");
            out.println(streamDescription);
            out.println();
            out.println("Stalls:");
            out.print(segmentStallNotes);
        } catch (IOException e) {
            LimeLog.warning("Stall capture: unable to write "+file+": "+e);
        }
    }

    private void writeLostCaptureNote() {
        File log = new File(dir, "lost-captures.txt");
        try (PrintWriter out = new PrintWriter(new FileOutputStream(log, true))) {
            out.println(new Date()+": a stall happened in a segment that couldn't be captured");
            out.print(segmentStallNotes);
        } catch (IOException ignored) {}
    }

    private void pruneOldCaptures() {
        File[] videos = dir.listFiles((d, name) -> name.startsWith("stall-") && name.endsWith(".h265"));
        if (videos == null) {
            return;
        }

        long totalBytes = 0;
        for (File video : videos) {
            totalBytes += video.length();
        }

        // Names sort chronologically, so delete from the front, but never the newest
        Arrays.sort(videos);
        for (int i = 0; i < videos.length - 1 && totalBytes > MAX_SAVED_BYTES; i++) {
            totalBytes -= videos[i].length();
            String base = videos[i].getName().replace(".h265", "");
            LimeLog.info("Stall capture: deleting old capture "+base+" to save space");
            for (String ext : new String[] {".h265", ".csv", ".txt"}) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, base+ext).delete();
            }
        }
    }

    private static String frameTypeName(int frameType) {
        return frameType == MoonBridge.FRAME_TYPE_IDR ? "IDR" : "P";
    }

    private static String bufferTypeName(int bufferType) {
        switch (bufferType) {
            case MoonBridge.BUFFER_TYPE_VPS: return "VPS";
            case MoonBridge.BUFFER_TYPE_SPS: return "SPS";
            case MoonBridge.BUFFER_TYPE_PPS: return "PPS";
            default: return "PIC";
        }
    }
}
