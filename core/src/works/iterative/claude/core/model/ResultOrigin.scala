package works.iterative.claude.core.model

// PURPOSE: Provenance of a result message — the wire `origin` key, absent on results that end an interactive turn
// PURPOSE: Open enumeration: only task-notification has been observed; other kinds survive verbatim in Other

enum ResultOrigin:
  /** A background task finished — `{"kind": "task-notification"}`. */
  case TaskNotification

  /** Any other `origin` value. `kind` is the wire `kind` string, or the raw
    * JSON text of the origin value when it has no string `kind` key.
    */
  case Other(kind: String)
