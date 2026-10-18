package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class StallCaptureReplayerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void parsesIndexIntoFrames() throws IOException {
        String csv = "frame_number,frame_type,buffer_type,byte_offset,length,ms_since_segment_start\n" +
                "2,IDR,VPS,0,27,0\n" +
                "2,IDR,SPS,27,54,0\n" +
                "2,IDR,PPS,81,11,0\n" +
                "2,IDR,PIC,92,100348,1\n" +
                "3,P,PIC,100440,133464,65\n" +
                "# frame 3 at 70 ms: stall detected after this frame\n" +
                "4,P,PIC,233904,56408,120\n";

        List<StallCaptureReplayer.Frame> frames = StallCaptureReplayer.parseIndex(
                new BufferedReader(new StringReader(csv)));

        assertEquals(3, frames.size());

        StallCaptureReplayer.Frame idr = frames.get(0);
        assertEquals(2, idr.frameNumber);
        assertTrue(idr.idr);
        assertEquals(3, idr.csd.size());
        assertArrayEquals(new long[] {27, 54}, idr.csd.get(1));
        assertEquals(1, idr.pictures.size());
        assertArrayEquals(new long[] {92, 100348}, idr.pictures.get(0));

        StallCaptureReplayer.Frame p = frames.get(1);
        assertEquals(3, p.frameNumber);
        assertFalse(p.idr);
        assertEquals(65, p.msSinceStart);
        assertTrue(p.csd.isEmpty());

        assertEquals(4, frames.get(2).frameNumber);
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedIndex() throws IOException {
        StallCaptureReplayer.parseIndex(new BufferedReader(new StringReader("header\n2,IDR,VPS\n")));
    }

    @Test
    public void parsesCaptureInfo() throws IOException {
        File txt = tmp.newFile("stall-test.txt");
        Files.write(txt.toPath(), ("Segment start: Sat Oct 17 20:19:46 PDT 2026\n" +
                "Segment size: 175169640 bytes\n" +
                "Decoder: c2.amlogic.hevc.decoder\n" +
                "Resolution: 3840x2160 at 60 FPS\n" +
                "Bitrate setting: 70000 Kbps\n" +
                "Video format: 0x100 (8-bit)\n" +
                "Configured format: {mime=video/hevc, width=3840, height=2160}\n").getBytes(StandardCharsets.US_ASCII));

        StallCaptureReplayer.CaptureInfo info = StallCaptureReplayer.CaptureInfo.parse(txt);
        assertEquals("c2.amlogic.hevc.decoder", info.decoderName);
        assertEquals(3840, info.width);
        assertEquals(2160, info.height);
        assertEquals(60, info.fps);
        assertFalse(info.tenBit);
    }

    private static StallCaptureReplayer.Result result(int framesIn, int framesOut, int lastFrameIn, int lastFrameOut, boolean drained) {
        StallCaptureReplayer.Result r = new StallCaptureReplayer.Result();
        r.framesIn = framesIn;
        r.framesOut = framesOut;
        r.lastFrameIn = lastFrameIn;
        r.lastFrameOut = lastFrameOut;
        r.drained = drained;
        return r;
    }

    @Test
    public void stuckDecoderThatStillReturnsEndOfStreamIsAPermanentStall() {
        // Soak run 12: the decoder stopped at frame 1715 but still passed end of stream through
        StallCaptureReplayer.Result r = result(2663, 1714, 2664, 1715, true);
        r.watchdogFrame = 1718;
        r.watchdogMs = 28650;
        assertTrue(r.permanentStall());
        assertTrue(r.reproduced());
        assertEquals(949, r.missingFrames());
        assertTrue(r.toString().contains("STALL REPRODUCED: output stopped after frame 1715"));
        assertTrue(r.toString().contains("watchdog would have fired at frame 1718"));
        assertFalse(r.toString().contains("Not reproduced"));
    }

    @Test
    public void missingEndOfStreamIsAPermanentStall() {
        StallCaptureReplayer.Result r = result(2663, 2000, 2664, 2001, false);
        assertTrue(r.permanentStall());
    }

    @Test
    public void skippedFramesWithOutputContinuingAreNotPermanent() {
        StallCaptureReplayer.Result r = result(2663, 2660, 2664, 2664, true);
        assertFalse(r.permanentStall());
        assertTrue(r.reproduced());
        assertTrue(r.toString().contains("Decoder skipped 3 frames"));
    }

    @Test
    public void cleanRunIsNotReproduced() {
        StallCaptureReplayer.Result r = result(2663, 2663, 2664, 2664, true);
        assertFalse(r.permanentStall());
        assertFalse(r.reproduced());
        assertTrue(r.toString().contains("Not reproduced: every frame decoded"));
    }

    @Test
    public void decoderRefusingInputIsAPermanentStall() {
        // In fast runs a stuck decoder's input queue fills up, so the run aborts on input
        StallCaptureReplayer.Result r = result(1500, 1490, 1501, 1491, false);
        r.abortReason = "decoder stopped accepting input at frame 1502";
        r.inputRefused = true;
        assertTrue(r.permanentStall());
        assertTrue(r.reproduced());
    }

    @Test
    public void stallListIsCapped() {
        StallCaptureReplayer.Result r = result(2663, 2663, 2664, 2664, true);
        for (int i = 0; i < 2600; i++) {
            r.addStall("no output for 60 ms after frame "+i);
        }
        assertEquals(20, r.stalls.size());
        assertEquals(2580, r.moreStalls);
        assertTrue(r.toString().contains("and 2580 more"));
        assertTrue(r.toString().length() < 2000);
    }

    @Test
    public void cancelledRunIsNotAStall() {
        StallCaptureReplayer.Result r = result(1000, 990, 1001, 991, false);
        r.abortReason = "cancelled";
        assertFalse(r.permanentStall());
    }

    @Test(expected = IOException.class)
    public void rejectsCaptureInfoWithoutDecoder() throws IOException {
        File txt = tmp.newFile("stall-bad.txt");
        Files.write(txt.toPath(), "Resolution: 1920x1080 at 60 FPS\n".getBytes(StandardCharsets.US_ASCII));
        StallCaptureReplayer.CaptureInfo.parse(txt);
    }
}
