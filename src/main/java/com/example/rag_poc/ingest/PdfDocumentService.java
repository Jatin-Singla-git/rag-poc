package com.example.rag_poc.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text from an uploaded PDF page by page and splits it into overlapping chunks.
 * Chunks never cross a page boundary, so every chunk has an exact page number.
 */
@Service
public class PdfDocumentService {

    /** One piece of text from a single page. */
    public record PdfChunk(int page, int chunkIndex, String text) {
    }

    public record PdfData(String filename, int totalPages, int extractedCharacters, List<PdfChunk> chunks) {
    }

    private static final int MAX_PAGES = 300;
    private static final int MAX_CHUNKS = 1000;
    private static final int CHUNK_SIZE = 1000;     // characters
    private static final int CHUNK_OVERLAP = 150;   // characters

    public PdfData parse(MultipartFile file) {
        String filename = file.getOriginalFilename();
        List<PdfChunk> chunks = new ArrayList<>();
        int characters = 0;
        int pages;

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            pages = document.getNumberOfPages();
            if (pages == 0) {
                throw bad("The PDF contains no pages.");
            }
            if (pages > MAX_PAGES) {
                throw bad("The PDF has " + pages + " pages; this POC supports at most " + MAX_PAGES + ".");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                characters += text.length();

                int index = 0;
                for (String piece : split(text)) {
                    chunks.add(new PdfChunk(page, index++, piece));
                }
                if (chunks.size() > MAX_CHUNKS) {
                    throw bad("The PDF produces more than " + MAX_CHUNKS
                            + " text chunks; this POC supports smaller documents.");
                }
            }
        } catch (InvalidPasswordException exception) {
            throw bad("The PDF is password-protected and cannot be read.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw bad("Could not read the PDF. Please check that it is a valid PDF file.");
        }

        if (chunks.isEmpty()) {
            throw bad("No text could be extracted from the PDF. It may be a scanned/image-only "
                    + "document; OCR is not supported.");
        }
        return new PdfData(filename, pages, characters, chunks);
    }

    /** Collapses whitespace so extracted slide text becomes clean, embeddable prose. */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    /** Splits text into ~CHUNK_SIZE pieces with CHUNK_OVERLAP, preferring to cut at whitespace. */
    private List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        if (text.isEmpty()) {
            return pieces;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int cut = text.lastIndexOf(' ', end);
                if (cut > start + CHUNK_SIZE / 2) {
                    end = cut;
                }
            }
            pieces.add(text.substring(start, end).trim());
            if (end >= text.length()) {
                break;
            }
            int next = end - CHUNK_OVERLAP;
            int space = text.indexOf(' ', next);
            start = (space != -1 && space < end) ? space + 1 : end;
        }
        return pieces;
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
