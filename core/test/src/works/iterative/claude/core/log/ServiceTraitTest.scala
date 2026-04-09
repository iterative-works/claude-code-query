// PURPOSE: Compilation tests verifying ConversationLogIndex and ConversationLogReader
// PURPOSE: can be implemented with both identity F (direct style) and IO (effectful style)

package works.iterative.claude.core.log

import munit.FunSuite
import cats.effect.IO
import works.iterative.claude.core.log.model.{
  ConversationLogEntry,
  LogFileMetadata,
  SubAgentMetadata
}

class ServiceTraitTest extends FunSuite:

  test("ConversationLogIndex compiles with identity F"):
    val _: ConversationLogIndex[[A] =>> A] =
      new ConversationLogIndex[[A] =>> A]:
        def listSessions(projectPath: os.Path): Seq[LogFileMetadata] = Seq.empty
        def forSession(
            projectPath: os.Path,
            sessionId: String
        ): Option[LogFileMetadata] = None
        def listSubAgents(
            projectPath: os.Path,
            sessionId: String
        ): Seq[SubAgentMetadata] = Seq.empty

  test("ConversationLogIndex compiles with IO"):
    val _: ConversationLogIndex[IO] = new ConversationLogIndex[IO]:
      def listSessions(projectPath: os.Path): IO[Seq[LogFileMetadata]] =
        IO.pure(Seq.empty)
      def forSession(
          projectPath: os.Path,
          sessionId: String
      ): IO[Option[LogFileMetadata]] =
        IO.pure(None)
      def listSubAgents(
          projectPath: os.Path,
          sessionId: String
      ): IO[Seq[SubAgentMetadata]] =
        IO.pure(Seq.empty)

  test("ConversationLogReader compiles with identity F and List stream type"):
    val _: ConversationLogReader[[A] =>> A] =
      new ConversationLogReader[[A] =>> A]:
        type EntryStream = List[ConversationLogEntry]
        def readAll(path: os.Path): List[ConversationLogEntry] = List.empty
        def stream(path: os.Path): List[ConversationLogEntry] = List.empty

  test("ConversationLogReader compiles with IO and fs2.Stream stream type"):
    val _: ConversationLogReader[IO] = new ConversationLogReader[IO]:
      type EntryStream = fs2.Stream[IO, ConversationLogEntry]
      def readAll(path: os.Path): IO[List[ConversationLogEntry]] =
        IO.pure(List.empty)
      def stream(path: os.Path): fs2.Stream[IO, ConversationLogEntry] =
        fs2.Stream.empty
