package com.pragmaticds.rag.service.ingestion;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts clean text from uploaded guideline files.
 * Apache Tika (via Spring AI's TikaDocumentReader) handles
 * PDF, DOCX, TXT, HTML, and Markdown with one code path.
 */
@Service
public class TextExtractionService {

    /**
     * @return extracted plain text, normalized for chunking.
     */
    public String extract(InputStream fileContent, String fileName) {
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(fileContent) {
            @Override
            public String getFilename() {
                return fileName; // lets Tika pick the right parser by extension
            }
        });

        List<Document> documents = reader.get();
        String text = documents.stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n\n"));

        return normalize(text);
    }

    /**
     * Light cleanup: collapse excessive blank lines and strip trailing spaces,
     * but preserve paragraph and heading structure for the chunker.
     */
    private String normalize(String text) {
        return text
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")     // trailing whitespace
                .replaceAll("\\n{3,}", "\n\n")      // 3+ blank lines -> one blank line
                .trim();
    }
}
