package com.jobai.knowledge.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MdPostProcessorSecurityTest {
    @Test
    void remoteAndLocalReferencesArePreservedWithoutFetchingOrOcr() {
        PaddleOcrService ocr = mock(PaddleOcrService.class);
        MdPostProcessor processor = new MdPostProcessor(ocr);
        String markdown = "# Notes\n![remote](http://127.0.0.1/private)\n"
                + "![local](C:/private/scan.png)\n![traversal](../../scan.png)";
        assertEquals(markdown, processor.ocrImages(markdown, Map.of()));
        verifyNoInteractions(ocr);
    }

    @Test
    void onlyExplicitlyUploadedBytesReachOcr() {
        PaddleOcrService ocr = mock(PaddleOcrService.class);
        MdPostProcessor processor = new MdPostProcessor(ocr);
        byte[] uploaded = {1, 2, 3};
        when(ocr.ocrImage(uploaded, "scan.png")).thenReturn("uploaded text");
        String enhanced = processor.ocrImages("![scan](scan.png) ![other](http://localhost/private)",
                Map.of("scan.png", uploaded));
        assertTrue(enhanced.contains("uploaded text"));
        assertTrue(enhanced.contains("![other](http://localhost/private)"));
        verify(ocr).ocrImage(uploaded, "scan.png");
        verifyNoMoreInteractions(ocr);
    }

    @Test
    void unavailableOcrDoesNotDiscardSourceImageReference() {
        PaddleOcrService ocr = mock(PaddleOcrService.class);
        when(ocr.ocrImage(any(), anyString())).thenReturn("");
        MdPostProcessor processor = new MdPostProcessor(ocr);
        assertEquals("![scan](scan.png)", processor.ocrImages("![scan](scan.png)", Map.of("scan.png", new byte[]{1})));
    }
}
