// PURPOSE: Serializes the newline-terminated JSON lines a session writes to the CLI's stdin
// PURPOSE: Pure wire encoders for structured user input and control requests, next to SDKUserMessage's encoder

package works.iterative.claude.core.model

import io.circe.Json
import io.circe.syntax.*

/** The lines a [[Session]] writes to the CLI process's stdin.
  *
  * User input is encoded with [[UserInput.encode]] into a content-block array,
  * so the verbatim user text is its own block and never concatenates with
  * context — injection is impossible by construction. Control requests use the
  * vendor control protocol correlated by `request_id`.
  */
object SessionStdin:

  /** The stdin line for a user input, newline-terminated. `message.content` is
    * the block array [[UserInput.encode]] produces.
    */
  def userInputLine(input: UserInput, sessionId: String): String =
    userMessageJson(UserInput.encode(input), sessionId).noSpaces + "\n"

  /** The stdin line for a control request, newline-terminated. */
  def controlRequestLine(request: ControlRequest): String =
    request.asJson.noSpaces + "\n"

  private def userMessageJson(
      blocks: List[TextBlock],
      sessionId: String
  ): Json =
    Json.obj(
      "type" -> Json.fromString("user"),
      "message" -> Json.obj(
        "role" -> Json.fromString("user"),
        "content" -> Json.arr(blocks.map(textBlockJson)*)
      ),
      "session_id" -> Json.fromString(sessionId)
    )

  private def textBlockJson(block: TextBlock): Json =
    Json.obj(
      "type" -> Json.fromString("text"),
      "text" -> Json.fromString(block.text)
    )
