# RAG POC – Question Answering Using Spring Boot

## 1. Objective

The objective of this POC is to build a simple question-answering application using RAG (Retrieval-Augmented Generation).

Users can upload Excel and PDF files and ask questions in natural language. The application finds relevant information from the uploaded files and uses an AI model to generate answers.

For Excel data, SQL is used for exact record lookups and numerical calculations. For descriptive questions, the application retrieves relevant information and uses an AI model to explain it.

The application can also select the appropriate file automatically when multiple files are uploaded.

## 2. What Is RAG?

RAG stands for Retrieval-Augmented Generation.

It combines two main steps:

1. **Retrieval:** Find information relevant to the user's question.
2. **Generation:** Use an AI model to create an answer based on the retrieved information.

For example, a user can ask, "What are the six fundamental principles of finance?"

The application retrieves the relevant content from the uploaded finance PDF and uses an AI model to generate the answer with page references.

Similarly, a user can ask, "Compare products and sales patterns in the West region." The application retrieves relevant Excel records and generates a summary.

## 3. Technologies Used

| Technology | Purpose |
|---|---|
| Java 21 | Application development |
| Spring Boot | REST APIs and application logic |
| Spring AI | Integration with AI models |
| Apache POI | Reading Excel files |
| Apache PDFBox | Extracting text from PDF files |
| Gemini Embeddings | Converting text into numerical vectors |
| PostgreSQL | Storing data persistently |
| pgvector | Storing embeddings and finding similar content |
| Groq | Generating answers and helping select relevant documents |

## 4. How We Built the POC

### Step 1: Upload documents

We created an API that accepts Excel and PDF files.

- For Excel files, the application reads the columns and rows.
- For PDF files, it extracts text from the pages and divides it into smaller chunks.

### Step 2: Convert content into embeddings

The application converts Excel rows and PDF text chunks into embeddings using the configured Gemini embedding model.

An embedding is a numerical representation of text. It helps the application find information that is related to a question, even when the exact words are different.

### Step 3: Store the data

The application stores the extracted content, embeddings, and metadata in PostgreSQL using pgvector.

This allows the application to reuse previously indexed data after a restart without requiring the files to be uploaded again.

### Step 4: Select the appropriate file

When a user asks a question, the application identifies which uploaded file is most relevant.

For example:

- Finance questions are matched with the finance PDF.
- SAML integration questions are matched with the Alegeus SAML PDF.
- Sales and revenue questions are matched with the Excel file.

The user can also specify a filename when they want to use a particular dataset.

### Step 5: Retrieve information

The application chooses a processing method based on the question.

- **Exact lookup:** Finds a particular record, such as an order ID.
- **SQL calculation:** Calculates totals and other numerical results from Excel data.
- **Semantic retrieval:** Finds relevant Excel rows or PDF text chunks for descriptive questions.

### Step 6: Generate the answer

For semantic questions, the application sends the retrieved content to Groq, which generates an answer based on that information.

For PDF questions, the answer can include page references to help the user locate the source content.

For exact Excel lookups and numerical calculations, SQL provides the result rather than asking the AI model to calculate it.

### Step 7: Return the response

The question API returns the answer along with information such as the source filename, processing mode, and document selection details.

## 5. Architecture

### Document upload and indexing

Excel/PDF file → Spring Boot → Extract content → Generate embeddings → PostgreSQL + pgvector

### Question answering

User question → Spring Boot → Select the relevant file → Choose the processing method

- **Exact record:** SQL lookup → Result
- **Numerical question:** SQL calculation → Result
- **Descriptive question:** Vector search → Relevant content → Groq → Answer
- **PDF question:** Vector search → Relevant text chunks → Groq → Answer with page references

The application then returns the response through the REST API.

## 6. APIs Implemented

### Upload API

`POST /api/documents/upload`

Uploads and indexes supported Excel and PDF files.

### Question API

`POST /api/documents/ask`

Accepts a question and returns an answer based on the uploaded data.

Example request:

```json
{
  "question": "Which SAML attributes are mandatory for TPA, Employer, and Partner users?"
}
```

The application can automatically select the Alegeus SAML PDF if it is available and relevant.

The existing chat API, `POST /api/chat`, is used for general chat and remains separate from document-based question answering.

## 7. POC Validation

We tested the application using an Excel file and PDF documents.

| Test | Result |
|---|---|
| Excel parsing and indexing | 36 rows indexed from the sales dataset |
| Exact order lookup | Retrieved details for ORD-00004 |
| SQL calculation | West net revenue: ₹1,281,280 |
| SQL calculation with negative values | West net profit: ₹240,967 |
| Excel semantic retrieval | Retrieved West-region records for sales comparisons |
| PDF upload and indexing | Successfully indexed a 21-page finance PDF |
| PDF question answering | Answered a question about the six fundamental principles of finance and referenced page 14 |
| SAML PDF question answering | Answered a question about SAML SSO using the Alegeus integration guide |
| Automatic file selection | Selected the relevant Excel or PDF file for tested questions |
| Persistent storage | Previously indexed data remained available after an application restart |

## 8. What This POC Demonstrates

The POC demonstrates that:

- Users can upload Excel and PDF files.
- Document content can be converted into embeddings and stored in a vector database.
- Users can ask questions in natural language.
- The application can retrieve relevant information using vector similarity search.
- An AI model can generate answers based on retrieved content.
- SQL can handle exact Excel lookups and numerical calculations.
- The application can automatically select a relevant document from multiple uploaded files.
- Data can remain available after an application restart.

## 9. Guardrails

The following protections are implemented. They reduce risk but do not remove it completely.

### Unsupported PDF answers

For a PDF question, the model returns its answer together with whether the retrieved excerpts support it and which excerpt numbers it used.

- If no excerpts are retrieved, the model says the excerpts do not support an answer, or it lists no valid supporting excerpt, the API returns `mode: "no_source"`, `sourceFilename: null` and no `sourcePages`.
- Otherwise `sourcePages` contains only the pages of the excerpts the model listed. Invalid excerpt numbers are ignored.
- If the structured model reply fails technically (error or unusable reply), the application falls back to a plain answer and lists all retrieved pages. That answer cannot be checked against the excerpts.
- When several files are selected, a file with no supporting content is left out of the combined answer. If none have support, the result is `no_source`.

### Question length and validation

- `POST /api/documents/ask` and `POST /api/chat` return HTTP 400 if the question is missing, blank, or longer than 2,000 characters (after trimming).
- Both endpoints use the same validation and messages.

### Untrusted document content

- Text taken from uploaded files is treated as data, not instructions. The prompts tell the model so, and the text is placed in `<context>`, `<candidates>` or `<sample_row>` blocks.
- Before any document text (excerpts, rows, filenames, column headers, calculation labels) is inserted into a prompt, any `<context>`, `<candidates>` or `<sample_row>` tag in it, opening or closing, is defused (`<` becomes `&lt;`). This stops document text from closing those blocks. The rest of the text is unchanged.

### Document selection when the selector fails

- If the model-based document selector fails, a document is chosen only when a cell value exactly matches an identifier in the question (for example an order ID) or the question contains the file's name.
- Otherwise the normal `no_source` result is returned. A document is never chosen from the similarity score alone.

### Remaining limitations

- Grounding relies on prompt instructions. The code does not check the answer text against the retrieved content, so an unsupported or inaccurate answer is still possible.
- The model decides what counts as supporting. It can list a wrong excerpt, omit a useful one, or declare a valid answer unsupported, which then returns `no_source`.
- Neutralizing delimiter tags does not stop plain-text instructions inside a document. It relies on the model following the "untrusted data" rules. The column list in the Excel planner prompt is not inside a delimiter block.
- Normal document selection depends on the model's judgment, so a wrong or missed file is possible.
- `/api/chat` is a general chat endpoint with no document grounding; it only validates the question.
- The upload size limit is 20 MB and the PDF limits are 300 pages and 1,000 chunks. No other upload checks, request timeouts, authentication or rate limits are configured.

## 10. Current Limitations

This is an initial POC, so some improvements are still needed.

- Automatic file selection can sometimes choose more than one file when only one is needed.
- Large prompts may exceed the AI model's token limits.
- Source pages for PDF answers depend on the model's judgment of which excerpts support the answer, so they may occasionally include a page that is not needed or miss a useful one.
- Semantic answers may contain inaccurate interpretations, so important answers should be checked against the original document.
- The solution has not been tested with every possible file, dataset, or question type.
- Production-level security, monitoring, performance, and scalability require further work.

## 11. Conclusion

This POC demonstrates how Spring Boot can combine RAG, vector search, SQL, and AI-generated answers in one application.

It supports Excel and PDF documents, retrieves relevant information, and generates answers to natural-language questions. SQL handles exact lookups and calculations, while vector search helps answer descriptive questions.

PostgreSQL and pgvector provide persistent storage for the indexed content and embeddings. The POC provides a foundation for exploring document-based question answering before extending it to additional data sources and business use cases.
