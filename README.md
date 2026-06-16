# Unofficial Claude Code Scala SDK

A thin wrapper around Claude Code CLI, modeled after the Python SDK with idiomatic Scala design patterns.

EXPERIMENTAL - this code is not yet properly tested or documented, work in progress.

## Features

- **Multiple API Styles**: Direct (Ox), effectful (cats-effect/fs2), or ZIO (ZIO/ZStream) over a shared core
- **Single Import**: Everything you need with `import works.iterative.claude.direct.*` (or `.effectful.*` / `.zio.*`)
- **Fluent Configuration**: Chain method calls to configure query options
- **Structured Concurrency**: Built on Ox for safe concurrent operations
- **Type Safety**: Full Scala 3 support with compile-time guarantees

## Quick Start

Add the dependency to your project. Use the `direct` module for Ox-based direct-style code, the `effectful` module for cats-effect/fs2, or the `zio` module for ZIO/ZStream:

```scala
// Mill
ivy"works.iterative::claude-code-query-direct:0.1.0"
ivy"works.iterative::claude-code-query-effectful:0.1.0"
ivy"works.iterative::claude-code-query-zio:0.1.0"

// SBT
"works.iterative" %% "claude-code-query-direct" % "0.1.0"
"works.iterative" %% "claude-code-query-effectful" % "0.1.0"
"works.iterative" %% "claude-code-query-zio" % "0.1.0"
```

## Simple API (Blocking)

Perfect for straightforward use cases where you want a simple blocking call:

```scala
import works.iterative.claude.direct.*

// Simple question
val answer = ClaudeCode.ask("What is 2+2?")
println(answer) // "4"

// With configuration
val options = QueryOptions.simple("Explain quantum computing")
  .withMaxTurns(3)
  .withModel("claude-3-5-sonnet-20241022")
  .withSystemPrompt("You are a physics teacher")

val result = ClaudeCode.queryResult(options)
println(result)

// Get all messages
val messages = ClaudeCode.querySync(options)
messages.foreach(println)
```

## Concurrent API (Async)

For advanced use cases where you need concurrent operations:

```scala
import works.iterative.claude.direct.*
import ox.*

supervised {
  val claude = ClaudeCode.concurrent

  // Single async query
  val answer = claude.ask("What is the capital of France?")

  // Multiple concurrent queries
  val queries = List(
    "What is 2+2?",
    "What is 3+3?",
    "What is 4+4?"
  )

  val results = queries.map { prompt =>
    fork { claude.ask(prompt) }
  }.map(_.join())

  println(results) // List("4", "6", "8")
}
```

## Advanced Configuration

Use the fluent API to configure all aspects of your queries:

```scala
import works.iterative.claude.direct.*

val options = QueryOptions.simple("Analyze this codebase")
  .withCwd("/path/to/project")
  .withMaxTurns(5)
  .withAllowedTools(List("Read", "Write", "Bash"))
  .withModel("claude-3-5-sonnet-20241022")
  .withSystemPrompt("You are a senior software engineer")
  .withPermissionMode(PermissionMode.AcceptEdits)
  .withTimeout(30.seconds)

val analysis = ClaudeCode.queryResult(options)
```

## Streaming Responses

Access the full message stream for detailed processing:

```scala
import works.iterative.claude.direct.*
import ox.*

supervised {
  val claude = ClaudeCode.concurrent
  val options = QueryOptions.simple("Write a story")

  val messageFlow = claude.query(options)

  messageFlow.runForeach { message =>
    message match {
      case AssistantMessage(content) =>
        content.foreach {
          case TextBlock(text) => print(text)
          case ToolUseBlock(id, name, input) => println(s"Using tool: $name")
          case _ =>
        }
      case SystemMessage(subtype, data) =>
        println(s"System: $subtype")
      case ResultMessage(_, duration, _, isError, _, sessionId, _, _, _) =>
        println(s"Completed in ${duration}ms, session: $sessionId")
      case _ =>
    }
  }
}
```

## Error Handling

Both APIs provide comprehensive error handling:

```scala
import works.iterative.claude.direct.*
import works.iterative.claude.core.{ProcessExecutionError, ConfigurationError}

try {
  val result = ClaudeCode.ask("Hello Claude")
  println(result)
} catch {
  case ProcessExecutionError(exitCode, stderr, command) =>
    println(s"CLI failed with exit code $exitCode: $stderr")
  case ConfigurationError(parameter, value, reason) =>
    println(s"Invalid configuration for $parameter=$value: $reason")
}
```

## Custom Logging

The SDK uses SLF4J by default, but you can provide your own logger:

```scala
import works.iterative.claude.direct.*

// Custom logger implementation
given Logger = new Logger {
  def debug(msg: => String): Unit = println(s"DEBUG: $msg")
  def info(msg: => String): Unit = println(s"INFO: $msg")
  def warn(msg: => String): Unit = println(s"WARN: $msg")
  def error(msg: => String): Unit = println(s"ERROR: $msg")
  def error(msg: => String, exception: Throwable): Unit =
    println(s"ERROR: $msg - ${exception.getMessage}")
}

val answer = ClaudeCode.ask("Hello with custom logging")
```

## Effectful API (cats-effect)

For applications using functional effect systems, the SDK provides a full effectful API with cats-effect IO and fs2:

```scala
import works.iterative.claude.effectful.*
import cats.effect.*
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

object EffectfulExample extends IOApp.Simple:
  def run = for {
    given logger <- Slf4jLogger.create[IO]

    // Simple query
    result <- ClaudeCode.queryResult(
      QueryOptions.simple("What is 2+2?")
    )
    _ <- IO.println(result)

    // Query with options
    options = QueryOptions.simple("Explain quantum computing")
      .withMaxTurns(3)
      .withModel("claude-3-5-sonnet-20241022")

    // Get all messages
    messages <- ClaudeCode.querySync(options)
    _ <- IO.println(s"Got ${messages.length} messages")

    // Stream processing
    _ <- ClaudeCode.query(options)
      .evalMap { message =>
        message match {
          case AssistantMessage(content) =>
            content.foreach {
              case TextBlock(text) => IO.print(text)
              case _ => IO.unit
            }
            IO.unit
          case _ => IO.unit
        }
      }
      .compile
      .drain
  } yield ()
```

### Error Handling with cats-effect

The effectful API provides composable error handling through IO:

```scala
import works.iterative.claude.effectful.*
import cats.effect.*
import cats.syntax.applicativeError.*
import org.typelevel.log4cats.slf4j.Slf4jLogger

val program = for {
  given logger <- Slf4jLogger.create[IO]

  result <- ClaudeCode.queryResult(
    QueryOptions.simple("Hello Claude")
  ).handleErrorWith {
    case ProcessExecutionError(exitCode, stderr, _) =>
      IO.pure(s"CLI failed with exit $exitCode: $stderr")
    case ConfigurationError(param, value, reason) =>
      IO.pure(s"Invalid $param=$value: $reason")
    case error =>
      IO.pure(s"Unexpected error: $error")
  }
  _ <- IO.println(result)
} yield ()
```

### Concurrent Queries with cats-effect

Leverage IO's fiber-based concurrency:

```scala
import works.iterative.claude.effectful.*
import cats.effect.*
import cats.syntax.parallel.*
import org.typelevel.log4cats.slf4j.Slf4jLogger

val queries = List("What is 2+2?", "What is 3+3?", "What is 4+4?")

val program = for {
  given logger <- Slf4jLogger.create[IO]

  // Run queries in parallel
  results <- queries.parTraverse { q =>
    ClaudeCode.queryResult(QueryOptions.simple(q))
  }
  _ <- IO.println(s"Results: $results")
} yield ()
```

### Streaming with fs2

The effectful API uses fs2 streams for efficient message processing:

```scala
import works.iterative.claude.effectful.*
import cats.effect.*
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

val program = for {
  given logger <- Slf4jLogger.create[IO]

  // Process messages as a stream
  _ <- ClaudeCode.query(options)
    .take(5)  // Take only first 5 messages
    .evalTap(msg => logger.info(s"Got message: ${msg.getClass.getSimpleName}"))
    .compile
    .toList
    .flatMap(messages => IO.println(s"First 5 messages: $messages"))
} yield ()
// Resources are automatically cleaned up via fs2's Resource management
```

## ZIO API

For applications built on ZIO, the SDK provides a ZIO-native API using `ZIO`, `ZStream`, and `zio-process`. Errors are surfaced through a typed `CLIError` channel rather than as exceptions, and logging uses ZIO's built-in logging (no logger needs to be threaded through):

```scala
import works.iterative.claude.zio.*
import zio.*

object ZioExample extends ZIOAppDefault:
  def run =
    for
      // Simple question
      answer <- ClaudeCode.ask("What is 2+2?")
      _      <- Console.printLine(answer)

      // Query with options
      options = QueryOptions
                  .simple("Explain quantum computing")
                  .withMaxTurns(3)
                  .withModel("claude-3-5-sonnet-20241022")

      // Get all messages
      messages <- ClaudeCode.querySync(options)
      _        <- Console.printLine(s"Got ${messages.length} messages")
    yield ()
```

### Streaming with ZStream

`query` returns a `ZStream[Any, CLIError, Message]` that emits messages as the CLI produces them:

```scala
import works.iterative.claude.zio.*
import zio.*
import zio.stream.*

val program: ZIO[Any, CLIError, Unit] =
  ClaudeCode
    .query(QueryOptions.simple("Write a story"))
    .collect { case AssistantMessage(content) => content }
    .runForeach: content =>
      ZIO.foreachDiscard(content):
        case TextBlock(text) => Console.printLine(text).orDie
        case _               => ZIO.unit
```

### Typed Error Handling

The ZIO API carries `CLIError` in the error channel, so failures are handled with the usual ZIO combinators:

```scala
import works.iterative.claude.zio.*
import zio.*

val program: UIO[String] =
  ClaudeCode
    .queryResult(QueryOptions.simple("Hello Claude"))
    .catchAll:
      case ProcessExecutionError(exitCode, stderr, _) =>
        ZIO.succeed(s"CLI failed with exit $exitCode: $stderr")
      case ConfigurationError(parameter, value, reason) =>
        ZIO.succeed(s"Invalid $parameter=$value: $reason")
      case error =>
        ZIO.succeed(s"Unexpected error: ${error.message}")
```

### Concurrent Queries

Leverage ZIO's fiber-based concurrency:

```scala
import works.iterative.claude.zio.*
import zio.*

val queries = List("What is 2+2?", "What is 3+3?", "What is 4+4?")

val results: ZIO[Any, CLIError, List[String]] =
  ZIO.foreachPar(queries): q =>
    ClaudeCode.queryResult(QueryOptions.simple(q))
```

### Multi-turn Sessions

`session` opens a scoped, long-lived conversation. The underlying process is shut down automatically when the scope closes:

```scala
import works.iterative.claude.zio.*
import zio.*

val program: ZIO[Any, CLIError, Unit] =
  ZIO.scoped:
    for
      session <- ClaudeCode.session(SessionOptions.defaults)
      _       <- session.send("Remember the number 42.")
      _       <- session.stream.runDrain
      _       <- session.send("What number did I ask you to remember?")
      answer  <- session.stream.runCollect
      _       <- Console.printLine(answer.mkString("\n"))
    yield ()
```

## Conversation Log Parsing

The SDK can read and parse the JSONL conversation logs that Claude Code writes to disk. Log files are stored under `~/.claude/projects/` in directories named after the project path.

### Listing Sessions (Direct API)

```scala
import works.iterative.claude.direct.*

val logDir = os.Path("/home/user/.claude/projects") / ProjectPathDecoder.decode("-home-user-myproject")

val index = DirectConversationLogIndex()
val sessions: Seq[LogFileMetadata] = index.listSessions(logDir)

sessions.foreach { meta =>
  println(s"Session ${meta.sessionId}, last modified ${meta.lastModified}")
  meta.summary.foreach(s => println(s"  Summary: $s"))
}
```

### Reading Log Entries (Direct API)

```scala
import works.iterative.claude.direct.*

val reader = DirectConversationLogReader()
val meta = DirectConversationLogIndex().listSessions(logDir).head

// Load all entries at once
val entries: List[ConversationLogEntry] = reader.readAll(meta.path)

entries.foreach { entry =>
  entry.payload match
    case UserLogEntry(content) =>
      content.collect { case TextBlock(text) => println(s"User: $text") }
    case AssistantLogEntry(content, model, usage, _) =>
      content.collect { case TextBlock(text) => println(s"Assistant: $text") }
      usage.foreach(u => println(s"  Tokens: ${u.inputTokens} in / ${u.outputTokens} out"))
    case _ =>
}
```

### Accessing Thinking Blocks

```scala
import works.iterative.claude.direct.*

val entries = DirectConversationLogReader().readAll(path)
entries.foreach { entry =>
  entry.payload match
    case AssistantLogEntry(content, _, _, _) =>
      content.foreach {
        case ThinkingBlock(thoughts, _) => println(s"Thinking: $thoughts")
        case RedactedThinkingBlock(_)   => println("(redacted thinking)")
        case TextBlock(text)            => println(s"Response: $text")
        case _                          =>
      }
    case _ =>
}
```

### Streaming Log Entries (Direct API)

```scala
import works.iterative.claude.direct.*
import ox.*

supervised {
  val reader = DirectConversationLogReader()
  reader.stream(path).runForeach { entry =>
    println(s"[${entry.timestamp}] ${entry.payload.getClass.getSimpleName}")
  }
}
```

### Listing and Reading Sessions (Effectful API)

```scala
import works.iterative.claude.effectful.*
import cats.effect.*

object LogExample extends IOApp.Simple:
  def run =
    val logDir = os.Path("/home/user/.claude/projects") /
      ProjectPathDecoder.decode("-home-user-myproject")

    val reader = EffectfulConversationLogReader()

    for
      index    <- EffectfulConversationLogIndex()
      sessions <- index.listSessions(logDir)
      _        <- IO.println(s"Found ${sessions.size} sessions")
      entries  <- reader.readAll(sessions.head.path)
      _        <- IO.println(s"Loaded ${entries.size} entries")
    yield ()
```

### Streaming Log Entries (Effectful API)

```scala
import works.iterative.claude.effectful.*
import cats.effect.*
import fs2.Stream

val reader = EffectfulConversationLogReader()

val program: IO[Unit] =
  reader.stream(path)
    .collect { case entry if entry.payload.isInstanceOf[AssistantLogEntry] => entry }
    .evalMap(entry => IO.println(s"Assistant turn: ${entry.uuid}"))
    .compile
    .drain
```

### Listing and Reading Sessions (ZIO API)

```scala
import works.iterative.claude.zio.*
import zio.*

object LogExample extends ZIOAppDefault:
  def run =
    val logDir = os.Path("/home/user/.claude/projects") /
      ProjectPathDecoder.decode("-home-user-myproject")

    val reader = ZioConversationLogReader()

    for
      index    <- ZioConversationLogIndex()
      sessions <- index.listSessions(logDir)
      _        <- Console.printLine(s"Found ${sessions.size} sessions")
      entries  <- reader.readAll(sessions.head.path)
      _        <- Console.printLine(s"Loaded ${entries.size} entries")
    yield ()
```

### Streaming Log Entries (ZIO API)

```scala
import works.iterative.claude.zio.*
import zio.*
import zio.stream.*

val reader = ZioConversationLogReader()

val program: ZIO[Any, Throwable, Unit] =
  reader.stream(path)
    .collect { case entry if entry.payload.isInstanceOf[AssistantLogEntry] => entry }
    .runForeach(entry => Console.printLine(s"Assistant turn: ${entry.uuid}"))
```

## Architecture

The SDK is split into four modules:

- **core**: Shared model types, JSON parsing, and CLI management. Depends only on circe.
- **direct** (`claude-code-query-direct`): Ox-based synchronous API. Depends on core + Ox + os-lib + SLF4J.
- **effectful** (`claude-code-query-effectful`): cats-effect/fs2 API. Depends on core + cats-effect + fs2 + log4cats.
- **zio** (`claude-code-query-zio`): ZIO/ZStream API with typed errors. Depends on core + zio + zio-streams + zio-process.

Technology stack:

- **Ox**: Structured concurrency for safe concurrent operations (direct API)
- **cats-effect IO + fs2**: Functional effects and streaming for the effectful API
- **ZIO + ZStream + zio-process**: Functional effects, streaming, and subprocess management for the ZIO API
- **Scala 3**: Modern language features and type safety
- **log4cats + SLF4J**: Flexible logging with both given-based and effectful approaches; the ZIO API uses ZIO's built-in logging

### Build Commands

```bash
mill __.compile        # compile all modules
mill __.test           # run unit tests (fast, no external dependencies)
mill __.itest          # run integration and E2E tests (requires Claude CLI)
mill __.publishLocal   # publish to local Maven repository
```

## Message Types

All Claude Code message types are available:

```scala
import works.iterative.claude.direct.*

val messages = ClaudeCode.querySync(options)

messages.foreach {
  case UserMessage(content) => println(s"User: $content")
  case AssistantMessage(content) =>
    content.collect { case TextBlock(text) => println(s"Assistant: $text") }
  case SystemMessage(subtype, data) => println(s"System ($subtype): $data")
  case ResultMessage(subtype, duration, _, isError, numTurns, sessionId, cost, usage, result) =>
    println(s"Result: $duration ms, $numTurns turns, session $sessionId")
}
```

## Requirements

- Scala 3.3+
- Mill 1.1+ (or SBT 1.9+) as build tool
- Claude Code CLI installed and available in PATH
- SLF4J compatible logging framework (optional)
