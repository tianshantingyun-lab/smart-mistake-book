# Image Pipeline Specification

> **Status: IMPLEMENTATION SPEC (direction confirmed; partially implemented).**
> Companion to `docs/image-pipeline-decision.md` (the decision record). This
> spec turns the confirmed direction into a buildable design. Items tagged
> **[verified]** were confirmed by external research or existing code;
> **[to-verify]** are open items (model reliability/cost) that need a pilot.
>
> **What actually runs today (2026-09-09):** the read/classify/redraw spine is
> wired. `RoomCaptureWorkflowRepository.decideAndRedraw` runs one
> `IMAGE_PIPELINE_CLASSIFY` round on the committed photo under the global model
> consent, and a `WITH_FIGURE` answer schedules `ConfiguredCleanImageGenerator`
> (`CleanImageGenerator`) to redraw and attach the clean figure. `TEXT_ONLY`
> problems keep the original photo and go through the existing local
> OCR/parse → typeset → PDF path, so no separate clean-sheet object is needed.
> The `ImageCleanSheet`/`ImageCleanSheetOrchestrator` pair this spec originally
> sketched was never called by production code and was removed on 2026-09-09
> (see git history). Still **not implemented**: the MCP figure path for the
> tutor "explaining" branch and any MathBox-style local typesetter of its own.

## 1. Goal & architecture (recap)

Turn a photographed problem into a clean, handwriting-free, printable problem
PDF for the mistake book, and generate tutoring figures while a multimodal
model explains.

```
user photographs problem
        │
        ▼
multimodal model (brain: read / classify / explain)          [read via chat-completions, image input]
        │
        ├─ [WITH FIGURE] → figure goes to MCP image-to-image  [gpt-image-2 via /v1/images/edits]
        │        └→ clean problem figure → problem PDF → mistake book
        ├─ [TEXT-ONLY] → multimodal reads structured content (Math AST / LaTeX)
        │        └→ local typesetter (MathBox) → clean problem → problem PDF
        └─ [EXPLAINING] → multimodal leads explanation
                 └→ tutoring figure → MCP image-to-image
```

**Models are split (per decision):**
- **Read / classify / explain**: any image-capable multimodal model the user
  configures (via the existing `OpenAiCompatibleModelGateway`, chat-completions,
  image input). **[verified]** the gateway already reads `ApprovedImage` →
  `EncodedImage` (base64) into the request.
- **Generate figures**: GPT image 2, exposed as an **MCP server** (figure
  generation is deliberately separated into MCP). **[verified]** `gpt-image-2`
  via `POST /v1/images/edits` (multipart: `images[]` input + `prompt`, output
  image base64).

## 2. Direction A — image-to-image generation (MCP + gpt-image-2)

**[verified]**
- Endpoint `POST /v1/images/edits`; multipart form:
  - `images[]`: the source photo (base64 data URL or file id), max 20 MB each,
    up to 16 images
  - `prompt`: edit instruction, 1–32000 chars
  - `model: gpt-image-2`; `size` (auto / 1024×1024 / 1536×1024 / 1024×1536);
    `quality` (low/medium/high/auto); `output_format` (png/jpeg/webp); `n` (1–10)
  - Response: `b64_json` (base64 image). `url` is unsupported for GPT models.
- MCP exposure: an MCP server wrapping this endpoint is the single figure path.
  Takes the photo + an instruction like "remove handwritten marks, keep printed
  problem text verbatim, redraw clean"; returns the clean image.

**[to-verify] — the open risk**
- gpt-image-2 is a **generative redraw**, not pixel-level restoration. It
  re-renders text, so dense printed pages risk character drift / alteration.
  **No documented guarantee** that it "erases handwriting while preserving
  printed text exactly". **Decision (accepted):** we accept a high-quality
  generative redraw (semantically correct, clean) rather than pixel-exact
  restoration — because the shared "takes GPT image 2 as the model" decision
  treats correctness of the redrawn problem (not photoreal restorations) as
  the goal. **Must pilot with real scanned samples** before shipping, and use
  `quality:"high"` with explicit "preserve printed text, remove only
  handwriting" phrasing.

## 3. Direction B — multimodal read / classify / explain

**[verified] — all present in code:**
- `OpenAiCompatibleModelGateway`: multimodal (image-capable) adapter. Reads
  image bytes via `RestrictedModelAssetSource`, attaches images to the request
  (`OpenAiModelProtocol.requestBody(..., images = images)`).
- Existing `ModelTaskKind` set covers the needed verbs:
  - `CAPTURE_ASSESS` / `CAPTURE_PARSE` — read the photographed problem
  - `TUTOR_PLAN` / `TUTOR_RESPOND` — explain
  - `TUTOR_VISUAL_GENERATE` / `TUTOR_VISUAL_REVIEW` — decide/review a figure

**Design (build on existing, do not fork):**
- The multimodal model reads the problem (image input), classifies it as
  **with-figure** vs **text-only**, and (for text-only) emits structured
  content (Math AST via the existing `MathAst`/`MathBox` pipeline).
- For "with-figure", the model emits a routing decision: the **figure** goes to
  MCP gpt-image-2; the model reads the text itself.
- For explaining, the model decides what tutoring figures it needs and emits
  them to MCP. **The model owns the routing decisions** (it decided the figure
  vs text-only split, and decides when to generate a tutoring figure).

## 4. Direction C — text-only problem typesetter

**[verified]** the in-house `MathBox` pipeline
(`Tokenizer→Parser→AST→Budget→BoxLayout→Compose draw`) renders the full
high-school formula set: Fraction, Radical, Superscript/Subscript, Matrix,
Cases, AlignedRows. `MathFormulaBox` is a `@Composable` drawing true fraction
bars, radical signs, matrices.

**Design:** for text-only problems, no MCP. The multimodal model reads the
problem into structured content (Math AST), and **MathBox lays it out** into a
clean problem sheet. No external LaTeX→image library is needed — MathBox is
Compose-native, offline, and already covers the formula grammar. (An external
library is only warranted if a formula grammar beyond MathBox crops up; not
expected for high-school.)

## 5. Routing summary

| Case | Read / understand | Produce figure | Produce text/formula sheet |
|---|---|---|---|
| With figure | multimodal (image input) | MCP gpt-image-2 | — |
| Text-only | multimodal | — | MathBox typesetter |
| Explaining (tutoring fig) | multimodal decides | MCP gpt-image-2 | — |

## 6. Open items to verify (pilot before ship)

1. **gpt-image-2 document-cleanup fidelity** — does it erase handwriting and
   keep printed text? (generative redraw, risk of character drift). Pilot with
   real scanned problems.
2. **Cost / latency** of gpt-image-2 (`$30/M` image output, standard; Org
   verification required before use; rate limits Tier1=5→Tier5=250 IPM).
3. **MCP server implementation** — what the figure-generation MCP server is
   (self-hosted wrapper around `/v1/images/edits`, or an existing one).

## 7. Implementation phases (suggested)

1. Pilot: gpt-image-2 cleanup fidelity on real problem photos. Gate on this.
2. MCP figure server (wrap `/v1/images/edits`).
3. Read/classify: multimodal task that outputs with-figure vs text-only, and
   (text-only) structured Math content.
4. Typesetter wiring: MathBox → clean problem sheet.
5. End-to-end: photograph → clean problem PDF into mistake book + tutoring
   figure path.
