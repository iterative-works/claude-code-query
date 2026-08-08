// PURPOSE: One entry of a result message's permission_denials list
// PURPOSE: Wraps the raw JSON verbatim; a populated entry's wire shape is vendor-defined and unsampled

package works.iterative.claude.core.model

import io.circe.Json

case class PermissionDenial(json: Json)
