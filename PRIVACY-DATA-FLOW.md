# Privacy and Data Flow

This application is local-first. The authoritative learning facts live in Room
and the canonical asset vault on the device.

When a user explicitly configures a BYOK model provider, authorized content
such as the current question, the current student message, and explicitly
granted source images may leave the device. The app never sends:

- API keys or authorization headers;
- Keystore private material;
- unrelated mistakes or full learning history;
- current-process leases or dispatch budgets;
- arbitrary URLs, code, SQL, or file paths returned by the model.

The user must approve disclosure before the first external send for a changed
provider, model, configuration, or disclosure scope. Process recreation never
auto-resumes an external send without an explicit user action.

Backup archives contain the learning database and canonical assets only. They
must never include provider credentials, leases, or cached authorization
manifests.
