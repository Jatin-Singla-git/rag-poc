package com.example.rag_poc;

import org.apache.poi.ss.usermodel.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.*;

/**
 * Parses an uploaded .xlsx file into an {@link ExcelData}.
 * Storage is handled by {@link ExcelVectorStoreService} (PostgreSQL/pgvector).
 */
@Service
public class ExcelDataService {

    /** Validates and parses the first sheet of the uploaded file. */
    public ExcelData parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Please upload an Excel file.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Only .xlsx files are supported.");
        }

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The Excel file contains no sheets.");
            }

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            if (headerRow == null || headerRow.getLastCellNum() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The first sheet has no header row.");
            }

            DataFormatter formatter = new DataFormatter();
            int columnCount = headerRow.getLastCellNum();

            List<String> headers = new ArrayList<>();
            for (int column = 0; column < columnCount; column++) {
                Cell cell = headerRow.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String header = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (header.isEmpty()) {
                    header = "Column_" + (column + 1);
                }
                headers.add(header);
            }

            List<Map<String, String>> records = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                Map<String, String> record = new LinkedHashMap<>();
                boolean hasData = false;

                for (int column = 0; column < columnCount; column++) {
                    Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (!value.isEmpty()) {
                        hasData = true;
                    }
                    record.put(headers.get(column), value);
                }

                if (hasData) {
                    records.add(record);
                }
            }

            if (records.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The first sheet has a header but no data rows.");
            }

            return new ExcelData(filename, sheet.getSheetName(), headers, records);

        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not read the Excel file. Please check its format.");
        }
    }
}
