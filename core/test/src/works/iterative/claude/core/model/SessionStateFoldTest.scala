package works.iterative.claude.core.model

// PURPOSE: Unit tests for the pure SessionState fold — the completion-counter decision as data-in/data-out
// PURPOSE: Pins that only origin-absent results bump resultsSeen and origin-present ones bump notificationsSeen

import munit.FunSuite
import io.circe.Json

class SessionStateFoldTest extends FunSuite:

  private def result(
      origin: Option[ResultOrigin],
      isError: Boolean = false,
      sessionId: String = "sess-1"
  ): ResultMessage =
    ResultMessage(
      subtype = "conversation_result",
      durationMs = 1,
      durationApiMs = 1,
      isError = isError,
      numTurns = 1,
      sessionId = SessionId(sessionId),
      origin = origin
    )

  test("an origin-absent result bumps resultsSeen and records lastResult"):
    val r     = result(origin = None)
    val state = SessionState.fold(SessionState.initial, r)
    assertEquals(state.resultsSeen, 1L)
    assertEquals(state.notificationsSeen, 0L)
    assertEquals(state.lastResult, Some(r))
    assertEquals(state.lastNotification, None)

  test("an origin-present result bumps notificationsSeen, not resultsSeen"):
    val r     = result(origin = Some(ResultOrigin.TaskNotification))
    val state = SessionState.fold(SessionState.initial, r)
    assertEquals(state.resultsSeen, 0L)
    assertEquals(state.notificationsSeen, 1L)
    assertEquals(state.lastNotification, Some(r))
    assertEquals(state.lastResult, None)

  test("an interrupted turn's error result (origin absent) bumps resultsSeen"):
    val r = result(origin = None, isError = true)
      .copy(subtype = "error_during_execution", result = None)
    val state = SessionState.fold(SessionState.initial, r)
    assertEquals(state.resultsSeen, 1L)
    assertEquals(state.lastResult.map(_.isError), Some(true))

  test("resultsSeen is monotone across many folds"):
    val messages = List(
      result(origin = None),
      result(origin = Some(ResultOrigin.TaskNotification)),
      result(origin = None),
      result(origin = Some(ResultOrigin.Other("something"))),
      result(origin = None)
    )
    val state = messages.foldLeft(SessionState.initial)(SessionState.fold)
    assertEquals(state.resultsSeen, 3L)
    assertEquals(state.notificationsSeen, 2L)

  test("a control response leaves the state unchanged"):
    val message = ControlResponse(RequestId("req-1"), "success", Json.obj())
    val state   = SessionState.fold(SessionState.initial, message)
    assertEquals(state, SessionState.initial)

  test("an assistant message leaves the counters unchanged"):
    val message = AssistantMessage(List(TextBlock("hi")))
    val state   = SessionState.fold(SessionState.initial, message)
    assertEquals(state, SessionState.initial)

  test("an unknown message leaves the state unchanged"):
    val message = UnknownMessage("rate_limit_event", Json.obj())
    val state   = SessionState.fold(SessionState.initial, message)
    assertEquals(state, SessionState.initial)

  test("ended records the session end without touching counters"):
    val folded = SessionState.fold(SessionState.initial, result(origin = None))
    val end    = SessionEnd(Some(0), None)
    val state  = SessionState.ended(folded, end)
    assertEquals(state.ended, Some(end))
    assertEquals(state.resultsSeen, 1L)
