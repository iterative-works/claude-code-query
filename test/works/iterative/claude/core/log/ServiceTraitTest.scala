// PURPOSE: Compilation tests verifying ConversationLogIndex and ConversationLogReader
// PURPOSE: can be implemented with both identity F (direct style) and IO (effectful style)

package works.iterative.claude.core.log

import munit.FunSuite
import cats.effect.IO
import works.iterative.claude.core.log.model.{
  ConversationLogEntry,
  LogFileMetadata
}

class ServiceTraitTest extends FunSuite:

  // Compilation test: ConversationLogIndex with identity F (direct style)
  test("ConversationLogIndex compiles with identity F"):
    val index: ConversationLogIndex[[A] =>> A] =
      new ConversationLogIndex[[A] =>> A]:
        def listSessions(projectPath: os.Path): Seq[LogFileMetadata] = Seq.empty
        def forSession(
            projectPath: os.Path,
            sessionId: String
        ): Option[LogFileMetadata] = None
    assert(index != null)

  // Compilation test: ConversationLogIndex with IO (effectful style)
  test("ConversationLogIndex compiles with IO"):
    val index: ConversationLogIndex[IO] = new ConversationLogIndex[IO]:
      def listSessions(projectPath: os.Path): IO[Seq[LogFileMetadata]] =
        IO.pure(Seq.empty)
      def forSession(
          projectPath: os.Path,
          sessionId: String
      ): IO[Option[LogFileMetadata]] =
        IO.pure(None)
    assert(index != null)

  // Compilation test: ConversationLogReader with identity F and List stream type
  test("ConversationLogReader compiles with identity F and List stream type"):
    val reader: ConversationLogReader[[A] =>> A] =
      new ConversationLogReader[[A] =>> A]:
        type EntryStream = List[ConversationLogEntry]
        def readAll(path: os.Path): List[ConversationLogEntry] = List.empty
        def stream(path: os.Path): List[ConversationLogEntry] = List.empty
    assert(reader != null)

  // Compilation test: ConversationLogReader with IO and fs2.Stream stream type
  test("ConversationLogReader compiles with IO and fs2.Stream stream type"):
    val reader: ConversationLogReader[IO] = new ConversationLogReader[IO]:
      type EntryStream = fs2.Stream[IO, ConversationLogEntry]
      def readAll(path: os.Path): IO[List[ConversationLogEntry]] =
        IO.pure(List.empty)
      def stream(path: os.Path): fs2.Stream[IO, ConversationLogEntry] =
        fs2.Stream.empty
    assert(reader != null)
