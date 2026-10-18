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

    @Test(expected = IOException.class)
    public void rejectsCaptureInfoWithoutDecoder() throws IOException {
        File txt = tmp.newFile("stall-bad.txt");
        Files.write(txt.toPath(), "Resolution: 1920x1080 at 60 FPS\n".getBytes(StandardCharsets.US_ASCII));
        StallCaptureReplayer.CaptureInfo.parse(txt);
    }
}
