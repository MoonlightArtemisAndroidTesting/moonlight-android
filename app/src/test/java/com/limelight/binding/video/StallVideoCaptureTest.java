package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class StallVideoCaptureTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] unit(int frameNumber, int bufferType) {
        return ("[" + frameNumber + ":" + bufferType + "]").getBytes(StandardCharsets.US_ASCII);
    }

    // Submits an IDR frame (VPS, SPS, PPS, picture) and returns the bytes written
    private static byte[] idr(StallVideoCapture capture, int frameNumber) {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int type : new int[] {MoonBridge.BUFFER_TYPE_VPS, MoonBridge.BUFFER_TYPE_SPS,
                MoonBridge.BUFFER_TYPE_PPS, MoonBridge.BUFFER_TYPE_PICDATA}) {
            byte[] data = unit(frameNumber, type);
            capture.onDecodeUnit(data, data.length, frameNumber, MoonBridge.FRAME_TYPE_IDR, type);
            expected.write(data, 0, data.length);
        }
        return expected.toByteArray();
    }

    private static byte[] pframe(StallVideoCapture capture, int frameNumber) {
        byte[] data = unit(frameNumber, MoonBridge.BUFFER_TYPE_PICDATA);
        capture.onDecodeUnit(data, data.length, frameNumber, MoonBridge.FRAME_TYPE_PFRAME, MoonBridge.BUFFER_TYPE_PICDATA);
        return data;
    }

    private static File[] captures(File dir, String ext) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("stall-") && name.endsWith(ext));
        Arrays.sort(files);
        return files;
    }

    @Test
    public void segmentWithoutStallIsDiscarded() throws Exception {
        File dir = tmp.newFolder();
        StallVideoCapture capture = new StallVideoCapture(dir, "test");
        idr(capture, 1);
        pframe(capture, 2);
        idr(capture, 3);
        capture.close();

        assertEquals(0, captures(dir, ".h265").length);
        assertEquals(0, capture.getSavedCaptureCount());
        assertTrue(!new File(dir, "segment.h265").exists());
    }

    @Test
    public void segmentWithStallIsSavedFromItsIdrFrame() throws Exception {
        File dir = tmp.newFolder();
        StallVideoCapture capture = new StallVideoCapture(dir, "Decoder: test");

        // First segment has no stall and must not appear in the capture
        idr(capture, 1);
        pframe(capture, 2);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(idr(capture, 3));
        expected.write(pframe(capture, 4));
        expected.write(pframe(capture, 5));
        capture.onStallDetected(5, "stall detected");
        expected.write(pframe(capture, 6));

        // The recovery IDR frame closes the stalled segment
        idr(capture, 7);
        capture.close();

        File[] videos = captures(dir, ".h265");
        assertEquals(1, videos.length);
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(videos[0].toPath()));
        assertEquals(1, capture.getSavedCaptureCount());

        String index = new String(Files.readAllBytes(captures(dir, ".csv")[0].toPath()), StandardCharsets.US_ASCII);
        assertTrue(index.contains("3,IDR,VPS,0,"));
        assertTrue(index.contains("# frame 5 at"));
        assertTrue(index.contains("stall detected"));

        String info = new String(Files.readAllBytes(captures(dir, ".txt")[0].toPath()), StandardCharsets.US_ASCII);
        assertTrue(info.contains("Decoder: test"));
        assertTrue(info.contains("stall detected"));
    }

    @Test
    public void stallInFinalSegmentIsSavedOnClose() throws Exception {
        File dir = tmp.newFolder();
        StallVideoCapture capture = new StallVideoCapture(dir, "test");
        idr(capture, 1);
        pframe(capture, 2);
        capture.onStallDetected(2, "stall detected");
        capture.close();

        assertEquals(1, captures(dir, ".h265").length);
    }

    @Test
    public void dataBeforeFirstIdrIsIgnored() throws Exception {
        File dir = tmp.newFolder();
        StallVideoCapture capture = new StallVideoCapture(dir, "test");
        pframe(capture, 1);
        byte[] expected = idr(capture, 2);
        capture.onStallDetected(2, "stall detected");
        capture.close();

        assertArrayEquals(expected, Files.readAllBytes(captures(dir, ".h265")[0].toPath()));
    }
}
