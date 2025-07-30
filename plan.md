# Implementation Plan: Fix Fake Streaming in Direct ClaudeCode API

## Problem Analysis

The issue exists specifically in the **direct implementation** (`works.iterative.claude.direct.ClaudeCode`) which uses Ox for structured concurrency. The current implementation has fake streaming:

```scala
// CURRENT BROKEN IMPLEMENTATION in direct/ClaudeCode.scala:32
def query(options: QueryOptions): Flow[Message] =
  Flow.fromIterable(executeQuery(options))  // ❌ FAKE STREAMING

private def executeQuery(options: QueryOptions): List[Message] =
  ClaudeCode.executeQuery(options)  // This blocks until completion
```

**Key Finding**: The **effectful implementation** (cats-effect/fs2) at `works.iterative.claude.effectful.ClaudeCode` **already has correct real streaming** and doesn't need to be fixed.

## Root Cause

The direct implementation's `ProcessManager.executeProcess()` method:
1. Starts the process
2. **Blocks** until the entire process completes using `process.waitFor()`
3. Reads **all stdout lines** at once after completion
4. Parses all JSON lines in batch
5. Returns complete `List[Message]`
6. The `query()` method then wraps this complete list in `Flow.fromIterable()`

This defeats the purpose of streaming entirely.

## Solution Strategy

Following the TDD methodology, we will implement **real streaming** for the direct implementation by:

### Phase 1: Top-Down Integration Tests
Write failing integration tests that verify:
1. Messages are emitted **as they arrive** from the CLI process
2. Early messages can be processed **before** the CLI completes
3. The Flow can be **terminated early** without waiting for process completion
4. **Backpressure** works correctly with slow consumers

### Phase 2: Real Streaming Implementation
Implement true streaming that:
1. **Starts process immediately** without waiting for completion
2. **Reads stdout line-by-line** as data becomes available
3. **Parses and emits messages** in real-time to the Flow
4. **Handles process lifecycle** properly (cleanup, error handling)
5. **Supports cancellation** and early termination

### Phase 3: Architecture Changes Required

#### Current Architecture (Blocking):
```
query() -> executeQuery() -> ProcessManager.executeProcess() 
         -> [BLOCKS until process complete] 
         -> List[Message] 
         -> Flow.fromIterable()
```

#### Target Architecture (Streaming):
```
query() -> Flow.source() 
        -> [Start process immediately] 
        -> [Read stdout lines as available] 
        -> [Parse & emit messages in real-time] 
        -> Flow[Message]
```

## Implementation Approach

### 1. Test-Driven Development Process

Following the TDD loop described in the methodology:

**RED Phase**: Write failing tests with `???` stubs
- Create streaming interface with stub implementation  
- Mock CLI process that emits data over time
- Test calls real interface, fails with `NotImplementedError`

**GREEN Phase**: Implement minimum code to pass
- Replace `???` with real streaming implementation
- Focus solely on making tests pass
- Use simplest approach that works

**COMMIT Process**: 
- Commit after each failing test: "Add failing test for [feature]"
- Commit after implementation: "Implement [feature]"

### 2. Key Technical Challenges

#### Challenge 1: Non-blocking Process Startup
- Current: `process.waitFor()` blocks until completion
- Target: Start process and return immediately, read from streams asynchronously

#### Challenge 2: Line-by-Line Stream Processing  
- Current: Read all stdout after process completes
- Target: Process stdout lines as they arrive using Ox concurrency

#### Challenge 3: Resource Management
- Ensure proper cleanup of process and streams
- Handle early Flow termination correctly
- Manage process lifecycle within Ox structured concurrency

#### Challenge 4: Error Handling During Streaming
- Handle process failures mid-stream
- Propagate stderr content for debugging
- Manage timeout scenarios during streaming

### 3. Ox-Specific Implementation Details

The implementation will use Ox's structured concurrency features:

```scala
def query(options: QueryOptions): Flow[Message] = 
  Flow.usingEmit[Message] { emit =>
    // Start process within supervised scope
    val process = startProcess(options)
    
    // Fork concurrent tasks for stdout/stderr
    par(
      // Stdout processing - emit messages as they arrive
      processStdoutLines(process, emit),
      // Stderr capture for error reporting  
      captureStderr(process)
    )
    
    // Handle process completion and cleanup
    handleProcessCompletion(process)
  }
```

### 4. Comparison with Working Effectful Implementation

The effectful implementation already has real streaming in `ProcessManager.executeProcess()`:

```scala
// From effectful/internal/cli/ProcessManager.scala:177-191
private def parseStdoutMessagesStream(process: fs2.io.process.Process[IO]): Stream[IO, Message] =
  process.stdout
    .through(fs2.text.utf8.decode)      // Real streaming: decode bytes as available
    .through(fs2.text.lines)            // Real streaming: emit lines as they arrive  
    .zipWithIndex
    .evalMap(parseJsonLine)             // Real streaming: parse each line immediately
    .evalMap(handleParseResult)
    .unNone
```

We need to implement equivalent functionality using Ox instead of fs2.

## Testing Strategy

### Top-Down Test Progression:

1. **Integration Test**: Complete user scenario with mock CLI that emits messages over time
2. **Streaming Behavior Test**: Verify messages arrive before process completion
3. **Early Termination Test**: Verify Flow can be cancelled mid-stream
4. **Error Handling Test**: Verify proper error propagation during streaming
5. **Resource Cleanup Test**: Verify proper cleanup when Flow terminates early

### Mock Strategy:
- Mock CLI executable that outputs JSON messages with delays
- Use real Ox Flow interface, not mocks
- Test actual streaming behavior, not mock interactions

## Success Criteria

✅ **Real Streaming**: Messages emitted as CLI process produces them  
✅ **Non-blocking**: Process starts immediately, doesn't wait for completion  
✅ **Early Access**: First messages available before CLI finishes  
✅ **Cancellation**: Flow can be terminated without waiting for process  
✅ **Resource Management**: Proper cleanup of processes and streams  
✅ **Error Handling**: Appropriate error propagation during streaming  
✅ **API Compatibility**: Existing `query()` method signature unchanged  
✅ **Performance**: No memory buildup from holding complete message lists  

## Implementation Order

Following TDD and top-down approach:

1. **Write Integration Test** - Test complete streaming scenario with mock CLI
2. **Implement Real Streaming** - Replace fake streaming with real implementation  
3. **Add Error Handling** - Proper stderr capture and error propagation
4. **Add Resource Management** - Ensure cleanup on early termination
5. **Add Timeout Support** - Handle timeout during streaming operations
6. **Verify Performance** - Ensure no memory leaks or resource issues

## Files to Modify

**Primary Changes:**
- `works/iterative/claude/direct/ClaudeCode.scala` - Replace fake streaming `query()` method
- `works/iterative/claude/direct/internal/cli/ProcessManager.scala` - Add streaming execution method

**Test Files:**
- Create new integration tests for streaming behavior
- Modify existing tests that assume blocking behavior

**No Changes Needed:**
- `works/iterative/claude/effectful/ClaudeCode.scala` - Already has real streaming
- Public API contracts remain unchanged