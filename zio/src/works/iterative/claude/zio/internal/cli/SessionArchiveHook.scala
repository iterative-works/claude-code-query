// PURPOSE: Optional best-effort archive mirroring fired after each real result and on session close
// PURPOSE: A mirror failure is logged and swallowed so it can never break the session

package works.iterative.claude.zio.internal.cli

import zio.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.zio.log.ZioConversationArchive

/** Hooks the session reader invokes to mirror the vendor transcript tree.
  *
  * Both hooks are best-effort: they never fail, so mirroring can never break
  * the session. A mirror failure is observable only as a logged warning.
  *
  * `afterResult` returns as soon as a mirror is scheduled, running the copy on
  * a background fiber so a slow tree copy never back-pressures the session
  * reader. `onClose` waits for any in-flight mirror and then settles the
  * archive to the full tree.
  *
  * @param afterResult
  *   fired with the session id after each real (origin-absent) result
  * @param onClose
  *   fired with the session id when the session ends
  */
final case class SessionArchiveHook(
    afterResult: String => UIO[Unit],
    onClose: String => UIO[Unit]
)

object SessionArchiveHook:

  /** A hook that does nothing — the default when no archive is configured. */
  val none: SessionArchiveHook =
    SessionArchiveHook(_ => ZIO.unit, _ => ZIO.unit)

  /** A hook that mirrors the session tree into the configured archive. A mirror
    * failure is logged as a warning and swallowed.
    *
    * A single permit serialises mirrors so two never run concurrently (and so
    * never corrupt the append-in-place copy). `afterResult` runs its mirror on
    * a background fiber and skips when one is already in flight, so it never
    * blocks the reader and completed turns cannot pile up. `onClose` takes the
    * permit, waiting for any in-flight mirror before running the final one.
    */
  def mirroring(config: ArchiveConfig): UIO[SessionArchiveHook] =
    Semaphore
      .make(1)
      .map: permit =>
        val archive = ZioConversationArchive(config)
        def mirror(sessionId: String): UIO[Unit] =
          archive
            .mirror(sessionId)
            .unit
            .catchAll: error =>
              ZIO.logWarning(
                s"Session archive mirror failed for '$sessionId': ${error.message}"
              )
        def afterResult(sessionId: String): UIO[Unit] =
          permit.tryWithPermit(mirror(sessionId)).forkDaemon.unit
        def onClose(sessionId: String): UIO[Unit] =
          permit.withPermit(mirror(sessionId))
        SessionArchiveHook(afterResult, onClose)
