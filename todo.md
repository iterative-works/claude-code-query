# TDD Implementation Todo: Ox Direct-Style Migration

This document outlines the test-driven development plan for migrating from cats-effect to Ox direct-style programming. We'll follow the "Red-Green-Commit" cycle, implementing tests in correct dependency order from bottom-up infrastructure to top-level API.

## Test Implementation Order

### Phase 1: Foundation Infrastructure

#### 1. File System Operations
**File**: `test/works/iterative/claude/direct/internal/cli/FileSystemOpsTest.scala`
**Dependencies**: None - direct system calls
**Priority**: Foundation - required by CLI discovery

- [x] **T1.1**: `which finds existing commands in PATH`
  - Test with 'sh' command (exists on Unix systems)
  - Tests: Real system PATH lookup without IO wrapper
  - Expected: `Option[String]` with path to sh executable

- [x] **T1.2**: `which returns None for non-existent commands`
  - Test with impossible command name
  - Tests: Graceful handling of missing commands
  - Expected: `None`

- [x] **T1.3**: `exists correctly identifies existing files`
  - Test with known existing file
  - Tests: File existence checking without IO effects
  - Expected: `Boolean` result

- [x] **T1.4**: `isExecutable correctly identifies executable files`
  - Test with sh executable
  - Tests: File permission checking
  - Expected: `Boolean` result for executable status

#### 2. CLI Discovery
**File**: `test/works/iterative/claude/direct/internal/cli/CLIDiscoveryTest.scala`
**Dependencies**: FileSystemOps (mocked)
**Priority**: Foundation - required by main API

- [x] **T2.1**: `findClaude succeeds when claude is found in PATH`
  - Mock FileSystemOps returning claude path from `which`
  - Tests: PATH lookup success case with direct-style
  - Expected: `Either[CLIError, String]` with `Right(path)`

- [x] **T2.2**: `findClaude falls back to common paths when PATH lookup fails`
  - Mock FileSystemOps with PATH failure, common path success
  - Tests: Fallback logic without IO effects
  - Expected: `Right(path)` from common installation paths

- [x] **T2.3**: `findClaude returns NodeJSNotFoundError when Node.js is missing`
  - Mock FileSystemOps returning no claude and no node
  - Tests: Prerequisite checking with direct-style error handling
  - Expected: `Left(NodeJSNotFoundError)` with installation guide

- [x] **T2.4**: `findClaude returns CLINotFoundError when claude not found anywhere`
  - Mock FileSystemOps returning no claude, but node available
  - Tests: Error case with actionable message
  - Expected: `Left(CLINotFoundError)` with installation instructions

- [ ] **T2.5**: `findClaude logs PATH search attempt and results`
  - Mock logger capturing log messages
  - Tests: Logging behavior during discovery
  - Expected: Debug and info messages logged appropriately

### Phase 2: JSON Processing

#### 3. JSON Parser Direct-Style
**File**: `test/works/iterative/claude/direct/internal/parsing/JsonParserTest.scala`
**Dependencies**: Core JSON parsing logic (reused from core package)
**Priority**: Core - required by ProcessManager

- [ ] **T3.1**: `parseJsonLineWithContext handles valid JSON messages`
  - Valid JSON message strings from CLI output
  - Tests: Direct-style parsing without IO wrapper
  - Expected: `Either[JsonParsingError, Option[Message]]` with parsed messages

- [ ] **T3.2**: `parseJsonLineWithContext handles empty lines gracefully`
  - Empty and whitespace-only strings
  - Tests: Graceful handling of empty input
  - Expected: `Right(None)` for empty lines

- [ ] **T3.3**: `parseJsonLineWithContext handles malformed JSON gracefully`
  - Invalid JSON strings with context
  - Tests: Error handling with line numbers
  - Expected: `Left(JsonParsingError)` with line context

- [ ] **T3.4**: `parseJsonLineWithContext logs parsing attempts`
  - Mock logger capturing debug messages
  - Tests: Logging integration in direct-style
  - Expected: Appropriate debug and error log messages

### Phase 3: Process Management

#### 4. ProcessManager Core Functionality
**File**: `test/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala`
**Dependencies**: JsonParser, Java ProcessBuilder
**Priority**: Core - heart of the system

- [ ] **T4.1**: `executeProcess returns Flow of messages from stdout`
  - Mock CLI executable outputting valid JSON messages
  - Tests: Basic process execution with Ox `supervised` and `Flow.usingEmit`
  - Expected: `Flow[Message]` with parsed messages from stdout

- [ ] **T4.2**: `executeProcess captures stderr concurrently`
  - Mock CLI that writes to both stdout and stderr
  - Tests: Concurrent stderr capture using Ox `fork`
  - Expected: Error information available when process fails

- [ ] **T4.3**: `executeProcess handles process failure with exit codes`
  - Mock CLI that exits with non-zero code and stderr
  - Tests: Error propagation with process context
  - Expected: `ProcessExecutionError` with exit code and stderr content

- [ ] **T4.4**: `executeProcess applies timeout when specified`
  - Mock hanging process with short timeout
  - Tests: Ox `timeout()` integration with processes
  - Expected: `ProcessTimeoutError` after specified duration

- [ ] **T4.5**: `executeProcess handles JSON parsing errors gracefully`
  - Mock CLI outputting malformed JSON
  - Tests: Error propagation from JSON parser through Flow
  - Expected: `JsonParsingError` with line context

- [ ] **T4.6**: `executeProcess logs process lifecycle events`
  - Any simple command with logging
  - Tests: Logging integration throughout process execution
  - Expected: Log entries for start, completion, and errors

#### 5. Process Configuration
**File**: `test/works/iterative/claude/direct/internal/cli/ProcessManagerTest.scala`
**Dependencies**: Java ProcessBuilder
**Priority**: Core - process setup

- [ ] **T5.1**: `configureProcess sets working directory when provided`
  - QueryOptions with cwd specified
  - Tests: Working directory configuration
  - Expected: Process configured with correct working directory

- [ ] **T5.2**: `configureProcess handles missing working directory gracefully`
  - QueryOptions with None for cwd
  - Tests: Default working directory behavior
  - Expected: Process uses current working directory

- [ ] **T5.3**: `configureProcess sets environment variables when specified`
  - QueryOptions with custom environment variables
  - Tests: Environment variable configuration
  - Expected: Process configured with specified environment

- [ ] **T5.4**: `configureProcess inherits environment when inheritEnvironment is true`
  - QueryOptions with inheritEnvironment=true
  - Tests: Environment inheritance behavior
  - Expected: Parent environment variables preserved

- [ ] **T5.5**: `configureProcess isolates environment when inheritEnvironment is false`
  - QueryOptions with inheritEnvironment=false
  - Tests: Clean environment setup
  - Expected: Only specified variables present, no inheritance

### Phase 4: Main API Streaming Interface

#### 6. ClaudeCode Core Streaming API
**File**: `test/works/iterative/claude/direct/ClaudeCodeTest.scala`
**Dependencies**: ProcessManager, CLIDiscovery
**Priority**: High - main user interface

- [ ] **T6.1**: `query with simple prompt returns Flow of messages`
  - Mock CLI executable outputting SystemMessage + AssistantMessage + ResultMessage
  - Tests: Complete flow from discovery → execution → parsing
  - Expected: `Flow[Message]` with all expected message types

- [ ] **T6.2**: `query handles CLI discovery when no explicit path provided`
  - QueryOptions without pathToClaudeCodeExecutable
  - Tests: Integration with CLIDiscovery
  - Expected: Successful execution after discovering CLI path

- [ ] **T6.3**: `query handles CLI discovery failure gracefully`
  - Mock CLIDiscovery returning CLINotFoundError
  - Tests: Error propagation from discovery phase
  - Expected: `CLINotFoundError` or `NodeJSNotFoundError`

- [ ] **T6.4**: `query validates configuration before execution`
  - QueryOptions with invalid working directory
  - Tests: Pre-execution validation
  - Expected: `ConfigurationError` before attempting process execution

- [ ] **T6.5**: `query passes CLI arguments correctly`
  - QueryOptions with various CLI parameters
  - Tests: Argument building and passing to subprocess
  - Expected: Correct arguments passed to CLI process

- [ ] **T6.6**: `query handles process execution errors`
  - Mock CLI that fails with non-zero exit code
  - Tests: Error propagation from ProcessManager
  - Expected: `ProcessExecutionError` with context

- [ ] **T6.7**: `query handles process timeout errors`
  - Mock CLI that hangs with timeout specified
  - Tests: Timeout handling through entire stack
  - Expected: `ProcessTimeoutError` after timeout duration

- [ ] **T6.8**: `query handles JSON parsing errors in stream`
  - Mock CLI outputting malformed JSON mid-stream
  - Tests: Error handling in streaming context
  - Expected: `JsonParsingError` with proper context

### Phase 5: Convenience API Methods

#### 7. ClaudeCode Convenience Methods
**File**: `test/works/iterative/claude/direct/ClaudeCodeTest.scala`
**Dependencies**: ClaudeCode.query()
**Priority**: High - user convenience

- [ ] **T7.1**: `querySync collects all messages from query Flow`
  - Mock CLI with multiple messages
  - Tests: Flow collection using Ox direct-style
  - Expected: `List[Message]` with all messages from Flow

- [ ] **T7.2**: `querySync propagates errors from underlying query`
  - Mock CLI that causes various error types
  - Tests: Error propagation through sync wrapper
  - Expected: Same errors as `query()` method

- [ ] **T7.3**: `queryResult extracts text from AssistantMessage`
  - Mock CLI outputting AssistantMessage with TextBlock
  - Tests: Message processing and text extraction
  - Expected: `String` with text content from AssistantMessage

- [ ] **T7.4**: `queryResult handles missing AssistantMessage gracefully`
  - Mock CLI outputting only SystemMessage and ResultMessage
  - Tests: Graceful handling of missing expected content
  - Expected: Empty string when no AssistantMessage found

- [ ] **T7.5**: `queryResult handles AssistantMessage without TextBlock`
  - Mock CLI outputting AssistantMessage with non-text content
  - Tests: Graceful handling of unexpected content types
  - Expected: Empty string when no TextBlock found

### Phase 6: Integration and Environment Tests

#### 8. Environment Configuration Edge Cases
**File**: `test/works/iterative/claude/direct/internal/cli/EnvironmentTest.scala`
**Dependencies**: ProcessManager
**Priority**: Medium - edge case handling

- [ ] **T8.1**: `handles environment variable names with special characters`
  - Environment variables with underscores, numbers, dots
  - Tests: Edge case handling in environment setup
  - Expected: All variables configured correctly

- [ ] **T8.2**: `handles environment variable values with special characters`
  - Values with spaces, quotes, newlines, unicode
  - Tests: Value preservation in process environment
  - Expected: Exact values preserved in subprocess

- [ ] **T8.3**: `handles empty environment variable names and values`
  - Edge cases with empty strings
  - Tests: Graceful handling of edge cases
  - Expected: Appropriate behavior (may reject or accept)

- [ ] **T8.4**: `process does not leak environment variable values in errors`
  - Process failure with secret environment variables
  - Tests: Error message security
  - Expected: Error messages don't contain sensitive values

#### 9. Full Integration Tests
**File**: `test/works/iterative/claude/direct/ClaudeCodeIntegrationTest.scala`
**Dependencies**: All components working together
**Priority**: High - complete system verification

- [ ] **T9.1**: `complete workflow with real mock CLI executable`
  - Full QueryOptions with all parameters
  - Tests: End-to-end integration with realistic mock
  - Expected: Complete message flow with proper types

- [ ] **T9.2**: `environment variable integration works end-to-end`
  - QueryOptions with custom environment variables
  - Tests: Environment variable passing through entire stack
  - Expected: Mock CLI receives and uses environment variables

- [ ] **T9.3**: `working directory integration works end-to-end`
  - QueryOptions with custom working directory
  - Tests: Working directory setting through entire stack
  - Expected: Mock CLI executes in specified directory

- [ ] **T9.4**: `real CLI discovery and execution (when available)`
  - QueryOptions without explicit CLI path
  - Tests: Real system integration (conditional on CLI availability)
  - Expected: Successful execution if Claude CLI is installed

## TDD Execution Strategy

### Red-Green-Commit Cycle

For each test above:

1. **RED Phase**:
   ```scala
   // Create stub implementation with ???
   object FileSystemOps:
     def which(command: String): Option[String] = ???
   ```
   - Write failing test that calls real interface
   - Verify test fails with `NotImplementedError`
   - **COMMIT**: "Add failing test for [T1.1] which finds existing commands"

2. **GREEN Phase**:
   ```scala
   // Replace ??? with minimal working implementation
   def which(command: String): Option[String] =
     // Minimal implementation to make test pass
   ```
   - Implement just enough to make test pass
   - Use real system calls or mocks as appropriate
   - **COMMIT**: "Implement [T1.1] which finds existing commands"

3. **REFACTOR Phase** (optional):
   - Improve code quality without changing behavior
   - **COMMIT**: "Refactor [T1.1] which implementation" (if changes made)

### Mock Strategy

- **Mock CLI executables**: Create test scripts in `test/bin/` that output expected JSON
- **Mock FileSystemOps**: Inject test doubles for CLI discovery tests
- **Mock Logger**: Capture log messages for verification  
- **Real system calls**: Use actual file system for FileSystemOps tests
- **Real ProcessBuilder**: Use actual Java ProcessBuilder for process tests

### Dependencies

Update `project.scala` before starting:
```scala
"com.softwaremill.ox" %% "core" % "1.0.0-RC2",
"com.softwaremill.ox" %% "flow" % "1.0.0-RC2"
```

## Success Criteria

- [ ] All tests pass with Ox direct-style implementation
- [ ] No cats-effect dependencies in `works.iterative.claude.direct` package
- [ ] Equivalent functionality to cats-effect version
- [ ] Proper resource cleanup using `supervised` scopes
- [ ] Error handling through exceptions or direct return values
- [ ] Integration tests work with real CLI executables
- [ ] Each component builds on tested foundation components