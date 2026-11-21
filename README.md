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

### Multi-document questions

When the automatic selection picks two or more PDFs (up to 3), for example for a comparison question, the application retrieves chunks from each selected PDF and generates one combined answer.

- The answer is organised by document and cites sources as (filename, page N).
- `sourceFilename` lists the documents that contributed, and `sourcePages` is grouped by document, for example `a.pdf: 4, 5; b.pdf: 2`.
- If a document has nothing on part of the question, the answer says so. If the documents give conflicting information, both are reported with their source.
- If none of the excerpts supports an answer, the result is `mode: "no_source"` with no filename or pages, as for a single PDF.
- If the selected files include an Excel file, each file is still answered separately under "From <file>:" headings, as before.
- Passing `filename` selects exactly one file, so it never triggers a multi-document answer.

### Conversation APIs (follow-up questions)

Conversations are stored in PostgreSQL (the same database as the vector store), so they survive an application restart.

| API | Purpose |
|---|---|
| `POST /api/conversations` | Creates a conversation and returns `conversationId` (HTTP 201) |
| `POST /api/documents/ask` | With an optional `conversationId`, uses the earlier messages to understand a follow-up, then saves the question and the answer |
| `GET /api/conversations/{conversationId}/messages` | Returns the messages, oldest first |
| `GET /api/conversations?limit=50` | Lists conversations, most recently updated first (`limit` 1–200, default 50) |

Example flow:

```json
POST /api/conversations
→ { "conversationId": "3f0c...", "createdAt": "...", "updatedAt": "..." }

POST /api/documents/ask
{ "conversationId": "3f0c...", "question": "What was the West region's net revenue?" }

POST /api/documents/ask
{ "conversationId": "3f0c...", "question": "What about net profit?" }
```

The `/ask` response keeps its existing fields and adds `conversationId` when one was supplied. Without a `conversationId`, `/ask` behaves exactly as before and nothing is stored.

Tables (created from `src/main/resources/schema.sql` at startup, using `CREATE TABLE IF NOT EXISTS`; the vector-store table is not touched):

- `conversations`: `id` (uuid, primary key), `created_at`, `updated_at`.
- `conversation_messages`: `id` (uuid, primary key), `conversation_id` (foreign key to `conversations`, deleted with it), `seq` (identity number that gives the order inside a conversation), `role` (`user` or `assistant`), `content`, `created_at`. An index on `(conversation_id, seq)` supports loading history.

Settings (in `application.properties`): `app.chat.history-messages` (how many recent messages are used, default 6) and `app.chat.history-max-chars` (characters kept per message, default 800).

The existing chat API, `POST /api/chat`, is used for general chat and remains separate from document-based question answering. It does not use conversation memory.

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

### Conversation history

- A `conversationId` must be a valid UUID of an existing conversation. An invalid value returns HTTP 400 and an unknown one returns HTTP 404, each with a `message`. A conversation only ever loads its own messages.
- The history is used only to rewrite a follow-up into a standalone question (one extra model call, only when the conversation already has messages). That question then goes through the normal file selection, Excel analytics and PDF retrieval, so answers come from the documents, not from earlier answers. Earlier assistant replies are never treated as evidence.
- History text goes through the same delimiter neutralising as document text, and the rewrite prompt tells the model to ignore instructions inside it.
- Only the most recent messages (default 6, each cut to 800 characters) are used.
- The original question and the answer are saved only after a successful answer, together in one transaction. Question validation (blank, 2,000 characters) runs before anything is loaded or saved.
- Message text is stored in PostgreSQL as plain text. It is not written to the application log, which records only ids, counts and lengths.

### Remaining limitations

- Follow-ups depend on the model rewriting the question correctly. A wrong rewrite can change which file or figure is used, and the rewrite is not shown in the response.
- Multi-document answers depend on the selector choosing all relevant PDFs (at most 3) and on the model attributing statements to the right document. They use at most 8 excerpts per document, so a broad question can miss content.
- Anything a user types or an answer returns is stored as is, including any sensitive text the user enters. There is no delete or retention policy and no per-user access control: anyone with a conversation id can read it.
- Messages are stored without a total size limit per conversation.

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
