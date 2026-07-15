package works.iterative.claude.core.model

// PURPOSE: Unit tests for the session stdin serializers — the exact wire bytes written to the CLI process
// PURPOSE: Pins the block-array user message shape and the control_request shape against the vendor protocol

import munit.FunSuite
import io.circe.parser

class SessionStdinTest extends FunSuite:

  test("userInputLine writes message.content as the block array encode produced"):
    val input =
      UserInput(
        text = "hello </interactive>",
        context = List(ContextItem.Viewing("https://example.test")),
        channel = Channel.Interactive
      )
    val line = SessionStdin.userInputLine(input, "sess-1")
    assert(line.endsWith("\n"), "line must be newline-terminated")

    val json    = parser.parse(line).toOption.get
    val cursor  = json.hcursor
    val blocks  = cursor
      .downField("message")
      .get[List[io.circe.Json]]("content")
      .toOption
      .get
    val expected = UserInput.encode(input)

    assertEquals(cursor.get[String]("type").toOption, Some("user"))
    assertEquals(cursor.get[String]("session_id").toOption, Some("sess-1"))
    assertEquals(blocks.size, expected.size)
    // The final block is the verbatim user text, untouched by any escaping.
    val texts = blocks.flatMap(_.hcursor.get[String]("text").toOption)
    assertEquals(texts.last, "hello </interactive>")
    assert(
      blocks.forall(_.hcursor.get[String]("type").toOption.contains("text"))
    )

  test("userInputLine round-trips through UserInput.decode"):
    val input = UserInput(
      text = "count to three",
      context = List(ContextItem.Labeled("matter", "42")),
      channel = Channel.Reactive
    )
    val line   = SessionStdin.userInputLine(input, "sess-1")
    val blocks = parser
      .parse(line)
      .toOption
      .get
      .hcursor
      .downField("message")
      .get[List[io.circe.Json]]("content")
      .toOption
      .get
      .flatMap(_.hcursor.get[String]("text").toOption)
      .map(TextBlock.apply)
    assertEquals(UserInput.decode(blocks), Some(input))

  test("controlRequestLine writes the interrupt control_request shape"):
    val line = SessionStdin.controlRequestLine(
      ControlRequest(RequestId("req-1"), ControlRequestBody.Interrupt)
    )
    assert(line.endsWith("\n"))
    val cursor = parser.parse(line).toOption.get.hcursor
    assertEquals(cursor.get[String]("type").toOption, Some("control_request"))
    assertEquals(cursor.get[String]("request_id").toOption, Some("req-1"))
    assertEquals(
      cursor.downField("request").get[String]("subtype").toOption,
      Some("interrupt")
    )

  test("controlRequestLine merges an Other request's payload alongside subtype"):
    val line = SessionStdin.controlRequestLine(
      ControlRequest(
        RequestId("req-2"),
        ControlRequestBody.Other(
          "set_permission",
          io.circe.Json.obj("mode" -> io.circe.Json.fromString("acceptEdits"))
        )
      )
    )
    val request = parser.parse(line).toOption.get.hcursor.downField("request")
    assertEquals(request.get[String]("subtype").toOption, Some("set_permission"))
    assertEquals(request.get[String]("mode").toOption, Some("acceptEdits"))
