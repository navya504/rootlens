# RootLens

RootLens is a local-first RCA demo built around a normal sample shopping website. Customers browse products and place mock orders; developers use a separate console to inspect application events, payment captures, and source-code evidence.

## Run locally on Windows

From PowerShell:

```powershell
cd "C:\Users\DBS\Documents\Codex\2026-10-15\i-x20\rootlens"
.\run.ps1
```

The launcher uses installed Java/Maven when available and otherwise downloads a JDK 21 and Maven into `.tools/` in this project. Maven dependencies are cached in `.tools/m2/`. No Docker is required. The script selects the first available port starting at 8080 and prints the URL.

- Storefront: `http://localhost:<printed-port>/`
- Developer RCA console: `http://localhost:<printed-port>/dev`
- Health: `http://localhost:<printed-port>/actuator/health`

Press Ctrl+C in the PowerShell window to stop the app.

## Explore the sample defects

- **Coupon pricing:** Add a product, apply `ROOT10`, and verify the cart preview and checkout both apply a fixed $10 discount (capped at the subtotal).
- **Duplicate payment:** Place an order over $100. The simulated gateway records a capture, times out ambiguously, and the order service retries with a different idempotency key. The developer console shows both captures.
- **Inventory oversell:** Buy the same product more than once. Inventory checks availability but does not decrement its count after a reservation.

Payments are simulated; the demo never contacts a payment provider or handles card data.

## Incident investigation and RCA

Open `/dev` to investigate the three reproducible shopping-app defects. The dark operations dashboard lists the bugs and real requests from the current app session. Selecting **Analyse defect** retrieves source evidence, explains the likely cause, shows remediation and ranked citations, and prepares an RCA report. The report can be exported as Markdown. Selecting a real request shows its observed event sequence; the interface does not invent span durations or incident timestamps.

Open `/view` for the Spring Boot shopping application dependency map. It shows the relationships among `ShopController`, `ShopService`, `InventoryService`, `PricingService`, `PaymentService`, and `ShopModels`, with source inspection for each component. The map is based on application service dependencies and does not expose RAG indexing internals.

The source search combines local BM25 with Pinecone semantic search when Pinecone is configured and indexed. Without Pinecone credentials, the app can use the local Ollama vector index or fall back to BM25. Citation scores rank retrieved evidence; they are not calibrated root-cause probabilities.

Selecting a request displays its correlated events in timestamp order. These events are observations from the local app session; the timeline does not invent span duration or imply that an unrelated request caused the selected catalog defect.

### Demo run-through

1. Start the app with `.\run.ps1`; open the printed storefront URL and `/dev` in separate tabs.
2. On `/dev`, wait for **HYBRID SOURCE SEARCH** before presenting vector scores. With Pinecone configured, select **Reindex source** after source changes and wait for completion. Without Pinecone or local embeddings, the app remains usable in BM25 mode and labels vector scores unavailable.
3. On `/dev`, select **Analyse defect** for the duplicate-payment defect. The first analysis creates a correlated demo trace with the timeout and retry sequence; ordinary storefront orders do not inject this payment fault.
4. Return to `/dev`. Select **Analyse request** on that order to show the correlated event timeline, capture ledger, source citations, root cause, and remediation. Open the RCA report and download it.
5. Analyse the coupon and inventory defects. If no matching request exists, the first analysis creates a correlated demo trace for that defect; the coupon trace contains no payment timeout or retry. Use **Apply fix to source** for the allow-listed demo patches, then close resolved defects from the queue. Open `/view` to inspect the radial shopping-service dependency graph.

Observed orders and mock payment records live in memory and reset when the app restarts. Pinecone vectors persist remotely; reindex after source changes.

### Pinecone vector search

RootLens can use the configured Pinecone serverless index with its integrated `llama-text-embed-v2` model (1024 dimensions, cosine metric). Pinecone generates embeddings for indexed code chunks and search queries; Ollama remains optional for local answer generation. Configure these environment variables before starting RootLens:

```powershell
$env:PINECONE_API_KEY = '<your Pinecone API key>'
$env:PINECONE_INDEX_HOST = 'rootlens-code-lp36wm1.svc.aped-4627-b74a.pinecone.io'
$env:PINECONE_NAMESPACE = 'rootlens-dev'
.\run.ps1
```

The API key is read only from the environment and must not be committed to the repository. Pinecone becomes the vector backend only when the key is present; otherwise the app retains the local vector/BM25 behavior. Code chunks are sent to Pinecone for indexing and semantic search, so use this mode only where that source-code transfer is allowed. Select **Reindex source** from `/dev` after configuring the connection.

### Optional local embeddings and answer generation

Without Ollama, RootLens runs local BM25 retrieval and built-in demo explanations. With Ollama, the app can make embeddings, blend BM25 with cosine similarity, save vectors to `.rootlens/code-vectors.tsv`, and generate evidence-grounded answers locally. Embedding diagnostics remain part of local retrieval configuration and are not shown on the shopping application dependency map.

1. Install and start Ollama for Windows. Keep it running while RootLens is open.
2. In PowerShell, download the configured models (about 3.2 GB total):

   ```powershell
   ollama pull qwen3-embedding:0.6b
   ollama pull qwen3:4b
   ```

3. Restart RootLens, open `/dev`, and press **Reindex source**. The source status indicates whether local vectors are ready. Bug analysis uses hybrid source retrieval when embeddings are available and falls back to BM25 otherwise.

Model names can be changed with `ROOTLENS_EMBEDDING_MODEL` and `ROOTLENS_CHAT_MODEL`; Ollama's URL can be changed with `OLLAMA_BASE_URL`. The embedding model is about 639 MB and the chat model about 2.5 GB according to Ollama's model library ([embedding model](https://ollama.com/library/qwen3-embedding), [chat model](https://ollama.com/library/qwen3%3A4b)). These downloads are optional; no model is downloaded by the app itself. If the embedding model is missing or Ollama is stopped, it stays in BM25 mode.

The vector index is a persistent local exact-search store suited to this small source set. It is not a distributed vector database. The current app is a local demonstration: the sample defects, in-memory orders, and mock payment ledger are not a production incident data pipeline.

