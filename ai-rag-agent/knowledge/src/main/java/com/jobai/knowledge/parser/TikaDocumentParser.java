package com.jobai.knowledge.parser;

import dev.langchain4j.data.document.Document;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class TikaDocumentParser implements DocumentParser {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB
    private static final int MAX_OCR_IMAGES = 50;               // limit images per doc
    private static final int MIN_IMAGE_BYTES = 1024;            // skip tiny images (< 1KB)

    private final PaddleOcrService paddleOcrService;

    public TikaDocumentParser(PaddleOcrService paddleOcrService) {
        this.paddleOcrService = paddleOcrService;
    }

    @Override
    public List<Document> parse(InputStream inputStream, String fileName) throws IOException {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

            Metadata tikaMeta = new Metadata();
            tikaMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);

            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            // Collect embedded images for OCR
            List<byte[]> extractedImages = new ArrayList<>();

            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override
                public void parseEmbedded(InputStream stream, org.xml.sax.ContentHandler ch,
                                          Metadata m, boolean b) throws SAXException, IOException {
                    if (extractedImages.size() >= MAX_OCR_IMAGES) return;
                    String contentType = m.get(Metadata.CONTENT_TYPE);
                    if (contentType != null && contentType.startsWith("image/")) {
                        byte[] imageBytes = readAllBytes(stream);
                        if (imageBytes.length >= MIN_IMAGE_BYTES) {
                            extractedImages.add(imageBytes);
                        }
                    }
                }

                @Override
                public boolean shouldParseEmbedded(Metadata m) {
                    return true;
                }
            });

            // PDF config: extract inline images, keep spatial ordering for text
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setSortByPosition(true);
            context.set(PDFParserConfig.class, pdfConfig);

            parser.parse(inputStream, handler, tikaMeta, context);

            // Merge extracted text with OCR from embedded images
            String text = handler.toString();
            if (!extractedImages.isEmpty() && paddleOcrService != null) {
                StringBuilder sb = new StringBuilder(text);
                sb.append("\n\n--- OCR from embedded images ---\n");
                for (int i = 0; i < extractedImages.size(); i++) {
                    String ocrText = paddleOcrService.ocrImage(extractedImages.get(i), fileName + "[img" + i + "]");
                    if (!ocrText.isBlank()) {
                        sb.append("\n[OCR:").append(i).append("]\n").append(ocrText).append("\n[/OCR]");
                    }
                }
                text = sb.toString();
            }

            return List.of(Document.from(text));
        } catch (SAXException | TikaException e) {
            throw new IOException("Tika parsing failed: " + e.getMessage(), e);
        }
    }

    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = stream.read(tmp)) != -1) {
            buffer.write(tmp, 0, n);
        }
        return buffer.toByteArray();
    }
}
