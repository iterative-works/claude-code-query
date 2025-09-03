# Architecture Overview

This document describes the architectural design of the Claude Code Scala SDK, a functional wrapper around the Claude Code CLI.

## Design Philosophy

The SDK follows these key principles:

- **Dual API Design**: Offers both direct-style (Ox-based) and effectful (cats-effect-based) APIs
- **Functional Core**: Uses immutable data structures and pure functions
- **Simplified Interface**: Flattens the complex TypeScript CLI output into clean, user-friendly Scala types
- **Separation of Concerns**: Clear boundaries between API, process management, CLI interaction, and parsing
- **Testability**: Components are designed for easy unit testing with dependency injection

## High-Level Architecture

```
┌─────────────────────────────────────────┐
│           Public API Layer              │
├──────────────────┬──────────────────────┤
│   Direct API     │    Effectful API     │
│   (Ox-based)     │ (cats-effect-based)  │
└──────────────────┴──────────────────────┘
            │                │
            └────────┬───────┘
                     │
         ┌──────────▼────────────┐
         │    Core Components    │
         ├───────────────────────┤
         │  • Model Types        │
         │  • CLI Discovery      │
         │  • Argument Builder   │
         │  • Process Manager    │
         │  • JSON Parser        │
         └───────────────────────┘
```

## Public API Layer

The SDK provides two distinct API styles to accommodate different programming preferences and application architectures:

### Direct-Style API (Ox-based)
**Location**: `works.iterative.claude.direct.ClaudeCode`

A synchronous, direct-style API using Ox for structured concurrency. Perfect for straightforward use cases and developers who prefer direct style over monadic effects.

**Key Methods**:
- `ask(prompt: String): String` - Simple blocking query returning text result
- `query(options: QueryOptions): Flow[Message]` - Returns streaming messages via Ox Flow
- `querySync(options: QueryOptions): List[Message]` - Returns all messages as a list
- `queryResult(options: QueryOptions): String` - Returns extracted text result

**Architecture Features**:
- **Structured Concurrency**: Uses Ox supervised blocks for safe concurrent operations
- **Streaming Support**: Real-time message streaming with early access via Flow
- **Simple Error Handling**: Standard try-catch with typed exceptions
- **Single Import**: Everything available via `import works.iterative.claude.direct.*`

### Effectful API (cats-effect + fs2)
**Location**: `works.iterative.claude.effectful.ClaudeCode`

A fully effectful API using cats-effect IO and fs2 streams for applications that embrace functional effect systems.

**Key Methods**:
- `query(options: QueryOptions)(using Logger[IO]): Stream[IO, Message]` - Returns fs2 stream of messages
- `querySync(options: QueryOptions)(using Logger[IO]): IO[List[Message]]` - Returns all messages in IO
- `queryResult(options: QueryOptions)(using Logger[IO]): IO[String]` - Returns extracted text in IO

**Architecture Features**:
- **Effect Management**: Full cats-effect IO integration for referential transparency
- **Streaming Support**: fs2 streams for memory-efficient message processing
- **Resource Safety**: Automatic cleanup via fs2's bracket and Resource abstractions
- **Composable Operations**: Leverage cats-effect's powerful combinators
- **Given-based Logging**: Uses log4cats with given instances for clean dependency injection

### Shared Architecture Pattern

Both APIs follow a **stepdown rule** with three levels:
1. **High-level "What" operations** - Public API methods that define what we want to accomplish
2. **Mid-level "How" operations** - Private methods that orchestrate the steps to accomplish the goals
3. **Low-level validation and utilities** - Specific implementation details and validations

### QueryOptions (Configuration)
**Location**: `works.iterative.claude.core.model.QueryOptions`

Comprehensive configuration case class that maps all CLI parameters to type-safe Scala options.

**Key Features**:
- Environment variable support (`inheritEnvironment`, `environmentVariables`)
- Tool permission management (`allowedTools`, `disallowedTools`, `permissionMode`)
- Conversation state (`continueConversation`, `resume`)
- Process control (`timeout`, `cwd`)
- Model and prompt configuration

## Model Layer

### Message Hierarchy
**Location**: `works.iterative.claude.model.Message`

Sealed trait hierarchy representing all message types from Claude Code CLI:

```scala
sealed trait Message
├── UserMessage(content: String)
├── AssistantMessage(content: List[ContentBlock])
├── SystemMessage(subtype: String, data: Map[String, Any])
└── ResultMessage(subtype, durationMs, cost, usage, ...)
```

### ContentBlock Hierarchy
**Location**: `works.iterative.claude.model.ContentBlock`

Represents structured content within assistant messages:

```scala
sealed trait ContentBlock
├── TextBlock(text: String)
├── ToolUseBlock(id, name, input)
└── ToolResultBlock(toolUseId, content, isError)
```

**Design Note**: These types are **deliberately simplified** compared to the full TypeScript SDK, hiding session IDs and internal complexity to provide a clean user experience.

## Internal CLI Management Layer

### ProcessManager
**Location**: `works.iterative.claude.internal.cli.ProcessManager`

**Purpose**: Manages subprocess execution and configuration for Claude Code CLI.

**Key Methods**:
- `configureProcessBuilder()` - Maps QueryOptions to ProcessBuilder configuration
- `executeProcess()` - Executes configured process and returns message stream

**Architecture Benefits**:
- **Separation of Concerns**: Process configuration is separate from execution
- **Testability**: ProcessBuilder configuration can be tested independently from actual process execution
- **Clean Abstractions**: Hides fs2 ProcessBuilder complexity from main API

**Implementation Details**:
- Uses fs2 ProcessBuilder for subprocess management
- Handles environment variable inheritance and overrides
- Manages working directory configuration
- Provides timeout support with graceful error handling
- Captures both stdout (for messages) and stderr (for error reporting)

### CLIDiscovery
**Location**: `works.iterative.claude.internal.cli.CLIDiscovery`

**Purpose**: Locates the Claude Code CLI executable on the system.

**Functionality**:
- Searches common installation paths
- Handles different OS environments
- Provides clear error messages when CLI is not found
- Supports explicit path override via QueryOptions

### CLIArgumentBuilder
**Location**: `works.iterative.claude.internal.cli.CLIArgumentBuilder`

**Purpose**: Converts QueryOptions parameters into CLI arguments.

**Key Features**:
- Maps each QueryOptions field to corresponding CLI flags
- Handles option presence/absence logic
- Formats complex parameters (like tool lists) correctly
- Ensures all SDK parameters are properly passed to CLI

### Error Types
**Location**: `works.iterative.claude.internal.cli.CLIError`

Comprehensive error hierarchy for different failure modes:

- `ProcessExecutionError` - CLI process failures with exit codes
- `JsonParsingError` - JSON parsing failures with line context
- `ProcessTimeoutError` - Process timeout with duration context
- `ConfigurationError` - Invalid configuration parameters

## Internal Parsing Layer

### JsonParser
**Location**: `works.iterative.claude.internal.parsing.JsonParser`

**Purpose**: Converts Claude Code CLI JSON output into typed Message objects.

**Architecture**: Multi-level parsing approach:

1. **High-level JSON line parsing** - Entry points with error handling
2. **Core message parsing** - Dispatches to specific message type parsers
3. **Message type parsers** - Handle specific message formats
4. **Content block parsing** - Handles different content types within messages
5. **Data extraction utilities** - Low-level JSON value extraction

**Key Features**:
- Streaming JSON parsing (line-by-line)
- Comprehensive error context (line numbers, content)
- Graceful handling of malformed JSON
- Detailed logging for debugging

**Error Handling Strategy**:
- Non-blocking: Invalid JSON lines are skipped with warnings
- Context-rich: Errors include line numbers and content for debugging
- Fail-fast: Critical parsing errors bubble up immediately

## Component Relationships

### Data Flow
```
QueryOptions → CLIArgumentBuilder → ProcessManager → CLI Process
                                                          ├── stdout → JsonParser → Message Stream
                                                          └── stderr → Error Context
```

### Dependency Structure
```
ClaudeCode (main API)
├── depends on: QueryOptions, ProcessManager, CLIDiscovery, CLIArgumentBuilder
│
ProcessManager
├── depends on: JsonParser, QueryOptions
│
JsonParser
├── depends on: Message types, ContentBlock types
│
Error handling flows through all layers with typed error contexts
```

### Testing Strategy

**Unit Testing**:
- Each component is tested independently
- ProcessManager configuration is tested separately from execution
- JsonParser handles various message formats and edge cases
- CLIArgumentBuilder verifies correct parameter mapping

**Integration Testing**:
- End-to-end CLI execution with real processes
- Environment variable handling
- Error propagation and logging
- Timeout and failure scenarios

**Architecture Benefits**:
- **Clean separation** enables focused unit tests
- **Dependency injection** allows mock implementations for testing
- **Pure functions** are easily testable without side effects
- **Typed errors** provide clear failure modes and testing scenarios

## Technology Stack Integration

### Direct API Stack

**Ox**: Structured concurrency library for direct-style Scala:
- Supervised blocks for safe resource management
- Flow abstraction for streaming data
- Direct-style error handling with exceptions
- Fork/join concurrency without monads

### Effectful API Stack

**cats-effect**: Industry-standard effect system:
- Composable error handling with IO monad
- Resource safety with bracket/Resource
- Integration with fs2 for streaming
- Fiber-based concurrency

**fs2**: Functional streaming library:
- Memory-efficient stream processing
- Backpressure handling
- Compositional stream operations
- Integration with cats-effect IO

**log4cats**: Effectful logging:
- Type-safe logging with IO
- Integration with SLF4J backends
- Given-based dependency injection

### Shared Components

**circe**: JSON parsing for both APIs:
- Type-safe JSON decoding with HCursor
- Comprehensive error information
- Functional programming patterns
- Line-by-line streaming support

**SLF4J**: Logging abstraction:
- Pluggable logging backends (logback included)
- Consistent logging across both APIs
- Direct API uses given-based SLF4J logger injection
- Effectful API uses log4cats with SLF4J backend

This dual-API architecture provides flexibility for users to choose their preferred programming style while sharing the same robust core implementation for CLI interaction and message processing.
