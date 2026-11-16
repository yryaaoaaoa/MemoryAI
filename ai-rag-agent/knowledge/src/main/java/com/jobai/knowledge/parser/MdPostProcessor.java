package com.jobai.knowledge.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class MdPostProcessor {

    private static final Pattern IMG_PATTERN = Pattern.compile("!\\[([^]]*)]\\(([^)]+)\\)");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private final PaddleOcrService paddleOcrService;

    /**
     * Process markdown: OCR images and split by headings.
     *
     * @param mdText   raw markdown text
     * @param uploadedImages image bytes explicitly supplied with this request, keyed by Markdown image reference
     * @return list of Documents, each with heading_path metadata
     */
    public List<Document> process(String mdText, Map<String, byte[]> uploadedImages) {
        // Step 1: OCR images and inject text
        String enhanced = ocrImages(mdText, uploadedImages);

        // Step 2: Split by heading hierarchy
        return splitByHeadings(enhanced);
    }

    /**
     * OCR only — returns enhanced text without heading splitting.
     */
    public String ocrImages(String mdText, Map<String, byte[]> uploadedImages) {
        // No images at all — fast path
        if (mdText == null || !mdText.contains("![")) return mdText;

        StringBuilder sb = new StringBuilder();
        Matcher m = IMG_PATTERN.matcher(mdText);
        int lastEnd = 0;

        while (m.find()) {
            sb.append(mdText, lastEnd, m.start());

            String altText = m.group(1);
            String imgPath = m.group(2);

            // Never dereference a URL or filesystem path supplied in Markdown. Only request attachments
            // may be passed to OCR; unresolved references remain intact instead of losing source content.
            byte[] bytes = uploadedImages == null ? null : uploadedImages.get(imgPath);
            String ocrText = bytes == null || bytes.length == 0 ? "" : paddleOcrService.ocrImage(bytes, imgPath);
            if (!ocrText.isEmpty()) {
                sb.append("\n[OCR:").append(altText.isEmpty() ? "图片" : altText).append("]\n")
                        .append(ocrText).append("\n[/OCR]\n");
            } else {
                sb.append(m.group());
            }

            lastEnd = m.end();
        }

        sb.append(mdText.substring(lastEnd));
        return sb.toString();
    }

    /**
     * Split markdown by heading boundaries. Produces Documents with heading_path metadata.
     * Non-heading preamble at the start is included as-is.
     */
    public static List<Document> splitByHeadings(String text) {
        List<Document> sections = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sections;
        }

        Matcher m = HEADING_PATTERN.matcher(text);
        int lastStart = 0;
        String lastHeadingPath = ""; // accumulated heading path, e.g. "MYSQL > SQL基础"
        int lastLevel = 0;

        if (!m.find()) {
            // No headings at all — entire text as one document
            return List.of(Document.from(text));
        }

        // Process the preamble before first heading
        if (m.start() > 0) {
            String preamble = text.substring(0, m.start()).trim();
            if (!preamble.isEmpty()) {
                sections.add(Document.from(preamble));
            }
        }

        // Now process each heading section
        int prevStart = m.start();
        String prevHeadingText = m.group(2).trim();
        int prevLevel = m.group(1).length();

        while (m.find()) {
            int currStart = m.start();
            String sectionContent = text.substring(prevStart, currStart);

            String sectionHeadingPath = buildHeadingPath(lastHeadingPath, lastLevel, prevHeadingText, prevLevel);

            Metadata meta = new Metadata();
            meta.put("heading_path", sectionHeadingPath);
            sections.add(Document.from(sectionContent, meta));

            // Update tracking
            lastHeadingPath = sectionHeadingPath;
            lastLevel = prevLevel;
            prevStart = currStart;
            prevHeadingText = m.group(2).trim();
            prevLevel = m.group(1).length();
        }

        // Last section
        String lastContent = text.substring(prevStart);
        String lastSectionHeadingPath = buildHeadingPath(lastHeadingPath, lastLevel, prevHeadingText, prevLevel);

        Metadata lastMeta = new Metadata();
        lastMeta.put("heading_path", lastSectionHeadingPath);
        sections.add(Document.from(lastContent, lastMeta));

        return sections;
    }

    private static String buildHeadingPath(String parentPath, int parentLevel,
                                            String currentHeading, int currentLevel) {
        // Reset path to appropriate level
        String basePath;
        if (currentLevel == 1) {
            basePath = currentHeading;
        } else if (currentLevel <= parentLevel) {
            // Going up in hierarchy: truncate to parent level
            String[] parts = parentPath.split("\\s*>\\s*");
            int keep = currentLevel - 1;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keep && i < parts.length; i++) {
                if (sb.length() > 0) sb.append(" > ");
                sb.append(parts[i]);
            }
            if (sb.length() > 0) sb.append(" > ");
            sb.append(currentHeading);
            basePath = sb.toString();
        } else {
            // Going deeper
            basePath = parentPath.isEmpty() ? currentHeading : parentPath + " > " + currentHeading;
        }
        return basePath;
    }
}
