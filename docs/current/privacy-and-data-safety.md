# Privacy and Play Data Safety Draft

## In-app privacy disclosure

The app is local-first. Learning records, canonical question images, review
history, and tutor conversations remain in app-private storage by default.

When the user configures a BYOK model provider and explicitly sends a request,
the app discloses exactly what leaves the device:

- the current question text and current student message;
- explicitly granted source images for the current capture session;
- the selected provider and model identity;
- a bounded teaching context that excludes unrelated mistakes and full history.

The app never sends API keys, Keystore material, leases, dispatch budgets, or
unrelated local facts.

## Data deletion

The storage screen offers:

- complete backup creation through the system document picker;
- backup validation before restore;
- staged restore with rollback on failure;
- delete-all-data with an explicit confirmation dialog.

Deleting all data removes the database, canonical assets, preferences, and
temporary files. The app should be restarted afterwards.

## Play Data safety answers

The following is a draft to be reviewed before upload:

- Data collected: none by the app itself; user-chosen BYOK requests are sent
  only to the user-selected provider endpoint.
- Data shared: only the explicitly disclosed current question/context and
  granted images during an authorized model request.
- Data encryption: local data is protected by app sandbox; backups use the
  system storage framework and are not cloud-synced.
- Deletion: in-app delete-all-data removes local records.
