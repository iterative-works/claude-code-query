# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an unofficial Scala SDK for Claude Code, designed as a thin wrapper around the Claude Code CLI. It follows the Python SDK architecture but adapted for idiomatic Scala with functional programming patterns.

## Build and Development Commands

- **Compile**: `scala-cli compile .`
- **Run**: `scala-cli run .` 
- **REPL**: `scala-cli repl .`
- **Package**: `scala-cli package .`

The project uses Scala CLI as the build tool with dependencies managed in `project.scala`.

## Architecture Overview

### Core Design Philosophy
The SDK follows the Python SDK's simplified approach rather than the more complex TypeScript SDK structure. It extracts and flattens the nested TypeScript `SDKMessage` format into clean, user-friendly Scala types.

### Key Components

**1. Message Types (`model.scala`)**
- `Message` - Sealed trait representing all message types from Claude Code CLI
- `UserMessage(content: String)` - User input messages  
- `AssistantMessage(content: List[ContentBlock])` - Assistant responses with structured content
- `SystemMessage(subtype: String, data: Map[String, Any])` - System initialization and metadata
- `ResultMessage(...)` - Final result with cost, timing, and usage information

**2. Content Blocks**
- `ContentBlock` - Sealed trait for message content components
- `TextBlock(text: String)` - Plain text content
- `ToolUseBlock(id, name, input)` - Tool invocation requests
- `ToolResultBlock(toolUseId, content, isError)` - Tool execution results

**3. Main API (`ClaudeCode.scala`)**
- `ClaudeCode` trait with single method: `query(options: QueryOptions): Stream[IO, Message]`
- `QueryOptions` case class with comprehensive CLI parameter mapping
- Uses fs2 streams for async message processing with cats-effect IO

### Technology Stack
- **Scala 3.3.6 LTS** - For maximum library compatibility
- **cats-effect 3.6.2** - Effect management and IO
- **fs2 3.12.0** - Functional streaming 
- **circe 0.14.14** - JSON parsing (for CLI output processing)

### Implementation Strategy
The SDK will:
1. Spawn Claude Code CLI as subprocess with `--output-format stream-json`
2. Parse streaming JSON responses into TypeScript `SDKMessage` format internally
3. Extract and flatten content to simple Scala `Message` types (hiding session IDs, wrapper complexity)
4. Provide fs2 stream of clean messages to users

This approach provides a clean, functional API while maintaining compatibility with the CLI's full feature set. Future implementations can add lower-level access if needed.

## Package Structure
- `works.iterative.claude` - Main package containing all SDK components
- Models and API are co-located for simplicity in early development phase