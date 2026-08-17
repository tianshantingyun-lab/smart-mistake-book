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
