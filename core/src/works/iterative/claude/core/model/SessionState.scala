// PURPOSE: The readable, monotone activity state of a session — the completion signal, folded from messages
// PURPOSE: resultsSeen counts only real (origin-absent) results; origin-present results bump notificationsSeen

package works.iterative.claude.core.model

/** The session's activity state, folded from the message stream.
  *
  * Read it to observe completion: `resultsSeen` is a monotone counter of real
  * turn completions, so a predicate like `resultsSeen > n` is stable once true
  * and readable at any time — there is no window to lose a race in. A
  * background task finishing bumps `notificationsSeen` instead, so it can never
  * wake a waiter for a real result.
  *
  * @param resultsSeen
  *   monotone count of real results (wire `origin` key absent)
  * @param notificationsSeen
  *   monotone count of background-task results (wire `origin` key present)
  * @param lastResult
  *   the most recent real result, if any
  * @param lastNotification
  *   the most recent background-task result, if any
  * @param ended
  *   set once the session's process has ended
  */
case class SessionState(
    resultsSeen: Long = 0L,
    notificationsSeen: Long = 0L,
    lastResult: Option[ResultMessage] = None,
    lastNotification: Option[ResultMessage] = None,
    ended: Option[SessionEnd] = None
)

object SessionState:
  val initial: SessionState = SessionState()

  /** Folds one message into the state. Pure and total.
    *
    * A result whose `origin` is absent is a real turn completion — including an
    * interrupted turn's error result, which is a completion, just a failed one
    * — and bumps `resultsSeen`. A result whose `origin` is present is a
    * background-task notification and bumps `notificationsSeen` instead. Every
    * other message leaves the state unchanged.
    */
  def fold(state: SessionState, message: Message): SessionState =
    message match
      case result: ResultMessage =>
        result.origin match
          case None =>
            state.copy(
              resultsSeen = state.resultsSeen + 1,
              lastResult = Some(result)
            )
          case Some(_) =>
            state.copy(
              notificationsSeen = state.notificationsSeen + 1,
              lastNotification = Some(result)
            )
      case _ => state

  /** Records the session's end in the state. */
  def ended(state: SessionState, end: SessionEnd): SessionState =
    state.copy(ended = Some(end))
