package works.iterative.claude.core.model

// PURPOSE: Control-channel request written to the CLI's stdin, answered by a ControlResponse
// PURPOSE: Correlated by the caller-chosen requestId; the request body vocabulary stays open

import io.circe.{Encoder, Json}

case class ControlRequest(requestId: String, request: ControlRequestBody)

object ControlRequest:
  /** Serializes a control request to the exact wire shape the CLI reads on
    * stdin:
    * `{"type":"control_request","request_id":…,"request":{"subtype":…}}`. The
    * `Other` case merges its payload fields alongside the subtype so an
    * unmodelled control request round-trips its arguments.
    */
  given Encoder[ControlRequest] = Encoder.instance: req =>
    Json.obj(
      "type" -> Json.fromString("control_request"),
      "request_id" -> Json.fromString(req.requestId),
      "request" -> requestBodyJson(req.request)
    )

  private def requestBodyJson(body: ControlRequestBody): Json =
    body match
      case ControlRequestBody.Interrupt =>
        Json.obj("subtype" -> Json.fromString("interrupt"))
      case ControlRequestBody.Other(subtype, payload) =>
        Json
          .obj("subtype" -> Json.fromString(subtype))
          .deepMerge(payload)

enum ControlRequestBody:
  /** Stop the running turn; the session survives. */
  case Interrupt

  /** Any other control request subtype. */
  case Other(subtype: String, payload: Json)
