// PURPOSE: Optional best-effort archive mirroring fired after each real result and on session close
// PURPOSE: A mirror failure is logged and swallowed so it can never break the session

package works.iterative.claude.zio.internal.cli

import zio.*
import works.iterative.claude.core.log.ArchiveConfig
import works.iterative.claude.core.model.SessionId
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
    afterResult: SessionId => UIO[Unit],
    onClose: SessionId => UIO[Unit]
)

object SessionArchiveHook:

  /** A hook that does nothing — the default when no archive is configured. */
  val none: SessionArchiveHook =
    SessionArchiveHook(_ => ZIO.unit, _ => ZIO.unit)

  /** Upper bound on how long `onClose` waits for the final mirror before it
    * logs a warning and moves on, so releasing an archive-configured session's
    * scope cannot block indefinitely on a slow filesystem copy.
    */
  val CloseMirrorTimeout: Duration = 30.seconds

  /** Runs a scheduled mirror in the background — the production behaviour. */
  private val forkDaemonRunner: UIO[Unit] => UIO[Unit] = _.forkDaemon.unit

  /** A hook that mirrors the session tree into the configured archive. Failures
    * are logged as warnings and swallowed: a whole-mirror failure and any
    * per-file failures within an otherwise-successful report are both surfaced
    * only as logs, so mirroring can never break the session.
    *
    * A single permit serialises mirrors so two never run concurrently (and so
    * never corrupt the append-in-place copy). `afterResult` hands its mirror to
    * `background` (forking a daemon fiber in production) and skips when one is
    * already in flight, so it never blocks the reader and completed turns
    * cannot pile up. `onClose` takes the permit, waiting for any in-flight
    * mirror before running the final one, bounded by [[CloseMirrorTimeout]].
    *
    * @param background
    *   how a scheduled `afterResult` mirror is run; the default forks a daemon
    *   fiber. A test may pass `identity` to run it synchronously.
    */
  def mirroring(
      config: ArchiveConfig,
      background: UIO[Unit] => UIO[Unit] = forkDaemonRunner
  ): UIO[SessionArchiveHook] =
    Semaphore
      .make(1)
      .map: permit =>
        val archive = ZioConversationArchive(config)
        def mirror(sessionId: SessionId): UIO[Unit] =
          archive
            .mirror(sessionId)
            .flatMap: report =>
              ZIO.when(report.failed.nonEmpty):
                ZIO.logWarning(
                  s"Session archive mirror for '${sessionId.value}' had " +
                    s"${report.failed.size} failed file(s): " +
                    report.failed.map(_._1).mkString(", ")
                )
            .unit
            .catchAll: error =>
              ZIO.logWarning(
                s"Session archive mirror failed for '${sessionId.value}': ${error.message}"
              )
        def afterResult(sessionId: SessionId): UIO[Unit] =
          background(permit.tryWithPermit(mirror(sessionId)).unit)
        def onClose(sessionId: SessionId): UIO[Unit] =
          permit
            .withPermit(mirror(sessionId))
            .timeout(CloseMirrorTimeout)
            .flatMap:
              case Some(_) => ZIO.unit
              case None    =>
                ZIO.logWarning(
                  s"Session archive final mirror for '${sessionId.value}' timed out " +
                    s"after ${CloseMirrorTimeout.toSeconds}s; moving on"
                )
        SessionArchiveHook(afterResult, onClose)
