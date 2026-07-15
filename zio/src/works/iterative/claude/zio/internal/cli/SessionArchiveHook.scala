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
    */
  def mirroring(config: ArchiveConfig): SessionArchiveHook =
    val archive = ZioConversationArchive(config)
    def mirror(sessionId: String): UIO[Unit] =
      archive
        .mirror(sessionId)
        .unit
        .catchAll: error =>
          ZIO.logWarning(
            s"Session archive mirror failed for '$sessionId': ${error.message}"
          )
    SessionArchiveHook(mirror, mirror)
