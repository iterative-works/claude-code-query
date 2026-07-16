// PURPOSE: Retries a transcript read that raced a vendor-tree prune between resolving and reading
// PURPOSE: Re-resolves once against the current root when the file vanished, else surfaces the error

package works.iterative.claude.zio.log

import zio.*
import zio.stream.*
import works.iterative.claude.core.log.ArchiveError
import works.iterative.claude.core.log.ArchiveError.ArchiveIOError

/** A vendor tree can be pruned in the gap between resolving a session's path
  * and actually reading it. The read then fails because the file no longer
  * exists; these helpers re-resolve the session once and retry against whatever
  * root now wins, so a live prune degrades to the mirror instead of an opaque
  * I/O error. A second failure is surfaced as-is: one retry only.
  */
object VanishedFileRetry:

  /** True when `t` or any error in its cause chain is a "file no longer exists"
    * failure — the signature of a path resolved just before it was pruned.
    */
  def isVanished(t: Throwable): Boolean =
    @annotation.tailrec
    def loop(cur: Throwable, seen: Set[Throwable]): Boolean =
      cur match
        case _: java.nio.file.NoSuchFileException     => true
        case _: java.io.FileNotFoundException         => true
        case ArchiveIOError(_, cause) if !seen(cause) =>
          loop(cause, seen + cur)
        case _ =>
          Option(cur.getCause) match
            case Some(next) if !seen(next) => loop(next, seen + cur)
            case _                         => false
    loop(t, Set.empty)

  /** Runs `read` against `path`. If it fails because the file vanished,
    * evaluates `reResolve` once for the path to retry against and reads that,
    * surfacing any second failure. `reResolve` fails with the caller's chosen
    * error when the session no longer resolves to a usable path at all.
    */
  def read[A](
      path: os.Path,
      read: os.Path => IO[ArchiveError, A],
      reResolve: IO[ArchiveError, os.Path]
  ): IO[ArchiveError, A] =
    read(path).catchSome:
      case e if isVanished(e) => reResolve.flatMap(read)

  /** Opens a stream for the session's resolved path. If reading fails because
    * the file vanished, re-resolves once and opens the new path, surfacing any
    * second failure. `whenGone` is used when nothing resolves.
    */
  def stream[A](
      open: os.Path => ZStream[Any, ArchiveError, A],
      resolve: IO[ArchiveError, Option[os.Path]],
      whenGone: => ArchiveError
  ): ZStream[Any, ArchiveError, A] =
    def opened: ZStream[Any, ArchiveError, A] =
      ZStream.unwrap:
        resolve.map:
          case Some(path) => open(path)
          case None       => ZStream.fail(whenGone)
    opened.catchSome:
      case e if isVanished(e) => opened
