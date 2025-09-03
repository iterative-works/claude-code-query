# Unofficial Claude Code Scala SDK

A thin wrapper around Claude Code CLI, modeled after the Python SDK with idiomatic Scala design patterns.

EXPERIMENTAL - this code is not yet properly tested or documented, work in progress.

## Features

- **Dual API Design**: Choose between simple blocking calls or concurrent operations
- **Single Import**: Everything you need with `import works.iterative.claude.direct.*`
- **Fluent Configuration**: Chain method calls to configure query options
- **Structured Concurrency**: Built on Ox for safe concurrent operations
- **Type Safety**: Full Scala 3 support with compile-time guarantees

## Quick Start

Add the dependency to your project:

```scala
// project.scala (using scala-cli)
//> using dep "works.iterative::claude-scala-sdk:0.1.0"
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

## Architecture

The SDK is built on:

- **Ox**: Structured concurrency for safe concurrent operations (direct API)
- **cats-effect IO + fs2**: Functional effects and streaming for the effectful API
- **Scala 3**: Modern language features and type safety
- **log4cats + SLF4J**: Flexible logging with both given-based and effectful approaches
- **Dual API Design**: Choose between direct style or effectful programming

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
- Claude Code CLI installed and available in PATH
- SLF4J compatible logging framework (optional)
