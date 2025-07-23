# Migration Plan: cats-effect → Ox Direct-Style Implementation

## Overview

This document outlines the migration from the current cats-effect based implementation to Ox direct-style programming. We'll create a new `works.iterative.claude.direct` package that mirrors the effectful package structure but uses Ox for concurrency and streaming.

## Key Architectural Changes

**Current cats-effect patterns → Ox direct-style equivalents:**

1. **IO[A] → A (blocking)** - Ox embraces blocking operations with Virtual Threads
2. **Stream[IO, A] → Flow[A]** - Ox flows for streaming data processing  
3. **Resource management** - `supervised` scopes with `useInScope`
4. **Concurrency** - `fork`/`supervised` instead of `IO.start`/`Fiber`
5. **Error handling** - Direct exceptions or `Either` with `.ok()` combinators

## Package Structure
```
works/iterative/claude/direct/
├── ClaudeCode.scala                    // Main API
├── internal/
│   ├── cli/
│   │   ├── ProcessManager.scala        // Direct-style process execution
│   │   ├── CLIDiscovery.scala         // Path discovery
│   │   └── FileSystemOps.scala        // File operations
│   └── parsing/
│       └── JsonParser.scala           // Stream parsing
```

## Dependencies

Update `project.scala` with Ox 1.0.0-RC2:

```scala
"com.softwaremill.ox" %% "core" % "1.0.0-RC2",
"com.softwaremill.ox" %% "flow" % "1.0.0-RC2"
```

## Detailed Migration Steps

### 1. Core API Transformation (`ClaudeCode.scala`)

**Before (cats-effect):**
```scala
def query(options: QueryOptions)(using logger: Logger[IO]): Stream[IO, Message]
def querySync(options: QueryOptions)(using logger: Logger[IO]): IO[List[Message]]
```

**After (Ox direct-style):**
```scala
def query(options: QueryOptions)(using logger: Logger, Ox): Flow[Message] 
def querySync(options: QueryOptions)(using logger: Logger, Ox): List[Message]
```

**Key changes:**
- Remove `IO` wrapper - operations are directly blocking
- Use `Flow[Message]` instead of `Stream[IO, Message]`
- `Ox` capability for structured concurrency
- Regular `Logger` instead of `Logger[IO]`

### 2. Process Management (`ProcessManager.scala`)

**Process execution transformation:**
```scala
// Current cats-effect
def executeProcess(
  processBuilder: ProcessBuilder,
  options: QueryOptions,
  executablePath: String, 
  args: List[String]
)(using logger: Logger[IO]): Stream[IO, Message]

// Ox direct-style  
def executeProcess(
  executablePath: String,
  args: List[String], 
  options: QueryOptions
)(using logger: Logger, Ox): Flow[Message]
```

**Implementation approach:**
- Use `supervised` scope for process lifecycle management
- `fork` for async stderr capture
- Ox `Flow.usingEmit` for streaming stdout parsing
- Native Java `ProcessBuilder` with blocking operations

### 3. Streaming and Parsing

**JSON parsing pipeline:**
```scala
// Current: fs2.Stream with IO effects
process.stdout
  .through(fs2.text.utf8.decode)
  .through(fs2.text.lines)
  .zipWithIndex
  .evalMap(parseJsonLine)

// Ox: Flow-based pipeline
Flow.usingEmit { emit =>
  val reader = process.stdout.bufferedReader()
  var lineNumber = 0
  reader.lines().forEach { line =>
    lineNumber += 1
    parseJsonLine(line, lineNumber) match
      case Some(message) => emit(message)
      case None => // skip empty lines
  }
}
```

### 4. Error Handling Strategy

**Structured error management:**
- **Application errors**: Use `Either[CLIError, A]` with `.ok()` combinators
- **System errors**: Direct exceptions for unexpected failures
- **Timeout handling**: Ox's built-in `timeout()` function
- **Resource cleanup**: `supervised` scopes ensure proper cleanup

### 5. Concurrency Patterns

**Replace fs2 concurrency with Ox:**
```scala
// Current: fs2.Stream.concurrently  
stdout.concurrently(Stream.eval(closeStdin))

// Ox: parallel execution in supervised scope
supervised {
  val stderrFork = fork {
    // capture stderr
    process.stderr.readAllBytes()
  }
  
  val messages = parseStdoutFlow(process.stdout)
  process.stdin.close()
  
  (messages, stderrFork.join())
}
```

## Implementation Priorities

### Phase 1: Core Infrastructure
1. **Dependencies**: Add Ox dependency to `project.scala`
2. **Basic API**: Implement direct-style `querySync` method
3. **Process execution**: Basic `ProcessManager` with Ox `supervised`
4. **Error handling**: Migrate error types to work with direct-style

### Phase 2: Streaming Support  
1. **Flow integration**: Implement `query` method returning `Flow[Message]`
2. **JSON parsing**: Stream-based parsing with `Flow.usingEmit`
3. **Timeout handling**: Integrate Ox timeout mechanisms
4. **Resource management**: Proper cleanup with `supervised` scopes

### Phase 3: Advanced Features
1. **Parallel execution**: File system discovery with `par()`
2. **Integration testing**: Verify compatibility with existing core models
3. **Performance optimization**: Leverage Virtual Threads efficiently
4. **Documentation**: Usage examples and migration guide

## Key Benefits of Ox Migration

1. **Simpler mental model**: Direct-style code without effect wrapping
2. **Better stack traces**: No effect library indirection  
3. **Virtual Thread optimization**: Efficient blocking operations
4. **Structured concurrency**: Predictable resource management
5. **Interop friendly**: Easier integration with existing Java libraries

## TDD Approach

We will follow a strict test-driven development approach:

1. **Start with integration tests** - Test complete user scenarios from the top level
2. **Mock external dependencies** - CLI executables, file system, network calls
3. **Work top-down** - Replace mocks incrementally with real implementations
4. **Red-Green-Commit cycle** - Failing test → implementation → commit

This ensures we build only what's needed and maintain high confidence in our implementation.