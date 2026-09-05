# Image Pipeline Decision

> **Status: CONFIRMED DIRECTION (not yet implemented).**
> This records the agreed technical direction for turning a photographed
> problem into a clean, printable problem PDF, and for generating tutoring
> figures. It describes the *target* architecture, not current code. Items are
> marked **[existing]** where the capability already exists in the codebase and
> **[to-add]** where it does not.

## Goal

A user photographs a problem and uploads it. The system must produce:

1. a **clean, handwriting-free, printable problem PDF** saved to the mistake
   book, and
2. tutoring figures generated on demand while the model explains the problem.

All understanding and explanation is owned by **a multimodal model**. Figures
are produced by an **image-to-image MCP server**.

## Architecture

```
user photographs problem
        │
        ▼
multimodal model  (brain: reads the problem, judges its type, explains it)
        │
        ├─ [WITH FIGURE]  → MCP image-to-image  ← receives the photo
        │        └→ renders a clean, handwriting-free problem figure
        │             └→ clean problem figure PDF  → mistake book
        │
        ├─ [TEXT-ONLY]   → multimodal reads structured content (incl. LaTeX)
        │        └→ local typesetter (NOT a figure renderer) → clean problem
        │             └→ problem PDF  → mistake book
        │
        └─ [EXPLAINING]  → multimodal leads the explanation
                 └→ needs a tutoring figure → MCP image-to-image
```

## Decisions

### 1. Multimodal model is the brain
Reads the problem, understands it, classifies whether it has a figure,
decides what figures to generate, and explains it. It owns all understanding
and explanation. **[existing]** (the codebase has `OpenAiCompatibleModelGateway`
as a multimodal read channel; `ModelTaskRegistry`/`ModelTasks` model the tasks).

### 2. All figures go to an image-to-image MCP server
Any figure — the clean redraw of a photographed figure, or a tutoring figure
the model decides to generate — is produced by an image-to-image MCP server.
This is the **only figure-producing path**. **[to-add]** (no MCP client exists
in code; MCP is a runtime/configured capability).

- The MCP server must be **image-to-image** (it receives the original photo
  and redraws a clean version). It must **not** be text-to-image only — an
  image-to-image model is required to keep the photographed problem intact
  while removing handwriting.

### 3. The local **scientific-figure renderer is abandoned**
The previous ad-hoc figure-rendering pipeline (semantic apparatus renderers,
hand-authored geometry) was dropped: it was unreliable, slow, and could not
faithfully reproduce the real-world figures in the photographs (measured ~30 %
similarity on a chemistry-apparatus figure). **All figure generation therefore
goes through MCP.** **[existing, now unused]** the `FigureSchema` /
`DeterministicPdfFigureRenderer` paths remain in the codebase but are not the
target for figure generation.

- **"Typesetter" is not a figure renderer.** The pure-text branch needs to lay
  out text and formulas (LaTeX) into a clean problem sheet. That is *typesetting*
  (reliable, deterministic) and is distinct from *figure rendering* (drawing a
  diagram). The typesetter is kept; the scientific-figure renderer is not.

### 4. OCR is dropped
The multimodal model reads text, formulas, and figures directly and is both
faster and less error-prone on formulas than a separate OCR pass. A dedicated
local OCR is therefore not part of the pipeline. **[to-remove]** the existing
ML Kit OCR (`mlkit-text-recognition-chinese`) integration is not part of the
target image pipeline.

### 5. Routing: text-only vs with-figure
- **[WITH FIGURE]** the *figure* goes to MCP image-to-image; the model reads the
  text itself.
- **[TEXT-ONLY]** no MCP. The multimodal model reads the problem into
  structured content (including LaTeX formulas), and the local typesetter lays
  it out into a clean problem sheet/PDF.

## Boundaries (clarified)

- **Figure renderer (draw a diagram) → abandoned, all via MCP.**
- **Typesetter (lay out text/formulas) → kept, used for the text-only branch.**
- **MCP must be image-to-image** (redraw the photographed problem cleanly),
  not text-to-image.
- **OCR is dropped**; the multimodal model reads everything.

## Cost risk (accepted)

Because MCP image-to-image is the only figure path and depends on an
image-capable model, cost concentrates there (the user chooses the model
provider, so cost follows the user's scenario: high-volume text-only is cheap
via the typesetter; figure-heavy or low-frequency-high-quality relies on the
image model).
