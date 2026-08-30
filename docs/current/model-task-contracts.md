# Model Task Contracts

Every `ModelTaskKind` has one machine-checkable contract:

- exact input fields
- exact disclosure set and prohibited disclosures
- asset policy (required / forbidden / exact grants)
- prompt policy version
- output schema and parser
- capability requirements
- max provider dispatches

Contract parity tests must lock:

- input JSON contains only allowed fields
- provider request body contains or omits images exactly as authorized
- wire keys, prompt policy, schema, and parser agree
- invalid output fails closed and never writes a business success
- optional model fields never trigger deletion
- model output cannot carry arbitrary routing, SQL, file paths, or URLs

`TUTOR_LOBBY` forbids images. Image tutoring routes through Capture before a
tutor plan/respond task may reference authorized canonical assets.

## Streaming (SSE) contract

Tutor text tasks (`TUTOR_PLAN`, `TUTOR_LOBBY`, `TUTOR_RESPOND`) may stream
their reply over Server-Sent Events. Non-streaming tasks (capture
assess/parse, visual generate/review, problem organization) always return a
single JSON completion and never request `stream=true`.

- A streaming request carries `"stream":true` and the transport reads
  `text/event-stream`, concatenating the delta `content` fields back into a
  chat-completion envelope.
- The gateway replays the deltas as `ModelGatewayEvent.Progress` frames
  (throttled every 8 SSE frames) so the persisted task enters `STREAMING` and
  the tutor UI renders the reply incrementally, then emits the terminal
  `Completed` with the fully parsed output.
- Streaming is only advertised (`supportsStreaming`) for a provider that
  passed the structured-output capability probe; an unverified provider gets a
  conservative non-streaming request.
- The terminal `Completed` always carries the complete output even when the
  reply streamed, so every consumer that ignores `Progress` still works.

