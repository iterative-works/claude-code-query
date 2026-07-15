package works.iterative.claude.core.model

// PURPOSE: One entry of a result message's permission_denials list
// PURPOSE: Wraps the raw JSON verbatim; a populated entry's wire shape is vendor-defined and unsampled

import io.circe.Json

case class PermissionDenial(json: Json)
