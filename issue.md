# Critical Issue: Fake Streaming Implementation in ClaudeCode.query()

## Problem Description

The current `ClaudeCode.query()` method claims to provide streaming via `Flow[Message]`, but it's actually implementing **fake streaming** that defeats the entire purpose of a streaming API.

## Current Broken Implementation

```scala
// In ClaudeCode class
def query(options: QueryOptions): Flow[Message] =
  Flow.fromIterable(executeQuery(options))  // ❌ FAKE STREAMING

def querySync(options: QueryOptions): List[Message] =
  executeQuery(options)  // This returns complete List[Message]

private def executeQuery(options: QueryOptions): List[Message] =
  ClaudeCode.executeQuery(options)  // Calls static method that waits for completion
```

### What Actually Happens:
1. `executeQuery()` **blocks** until the entire Claude CLI process completes
2. All stdout is read and parsed into a complete `List[Message]`
3. **Only then** do we create a `Flow.fromIterable()` from the completed list
4. The "streaming" Flow just iterates over pre-computed data

## Why This Is Broken

### Performance Issues:
- **No early data access**: Can't process messages as they arrive
- **Memory inefficiency**: Must hold entire conversation in memory
- **Blocking behavior**: No responsiveness during long Claude operations
- **False advertising**: Streaming API that doesn't actually stream

### User Experience Issues:
- **Long wait times**: Users see nothing until entire response is complete
- **No progress indication**: Can't show partial responses or thinking progress
- **Resource waste**: Process stdout buffer fills up unnecessarily

### Technical Issues:  
- **API contract violation**: `Flow[Message]` implies streaming, but delivers batch processing
- **Concurrency problems**: Multiple "streaming" calls still block completely
- **Missing backpressure**: Can't control processing rate of messages

## Expected Real Streaming Behavior

A true streaming implementation should:

1. **Start process immediately**
2. **Read stdout line-by-line as data arrives**
3. **Parse each JSON line immediately**
4. **Emit messages to Flow in real-time**
5. **Allow early termination/cancellation**

```scala
// What streaming SHOULD look like conceptually
def query(options: QueryOptions): Flow[Message] = {
  // Start process
  val process = startClaudeProcess(options)
  
  // Create Flow that reads from stdout as data arrives
  Flow.fromInputStream(process.getInputStream)
    .map(parseJsonLine)
    .collect { case Some(message) => message }
}
```

## Impact Assessment

### Current State:
- ✅ Sync API (`querySync`, `queryResult`) works correctly for batch processing
- ❌ Streaming API (`query`) is completely broken for its intended purpose
- ❌ Documentation promises streaming capabilities that don't exist
- ❌ Performance characteristics are identical between "streaming" and sync APIs

### User Expectations vs Reality:
- **Expected**: Real-time message processing as Claude responds
- **Actual**: Same blocking behavior as sync API, just wrapped in Flow

## Proposed Solution Areas

### Option 1: Fix Real Streaming
- Implement true streaming from process stdout
- Parse JSON lines as they arrive
- Emit to Flow immediately
- Handle backpressure and cancellation

### Option 2: Remove Fake Streaming  
- Remove `query()` method entirely
- Keep only honest sync APIs (`querySync`, `queryResult`)
- Update documentation to reflect actual capabilities

### Option 3: Hybrid Approach
- Keep current batch-based implementation for `querySync`
- Implement real streaming for `query()` 
- Clearly document the difference

## Technical Requirements for Real Streaming

1. **Process Management**: Start process without waiting for completion
2. **Stream Processing**: Read stdout lines as they become available
3. **JSON Parsing**: Parse individual lines immediately (not in batch)
4. **Error Handling**: Handle process failures during streaming
5. **Resource Cleanup**: Ensure proper cleanup if Flow is terminated early
6. **Backpressure**: Handle slow consumers properly
7. **Concurrency**: Multiple streaming operations should work independently

## Current Architecture Problems

The issue stems from the architecture where:
- `ProcessManager.executeProcess()` blocks until completion
- All JSON parsing happens after process finishes
- `Flow` is just a post-hoc wrapper around completed data

Real streaming requires:
- Non-blocking process startup
- Line-by-line stdout processing  
- Immediate JSON parsing and message emission
- Proper resource management during streaming

## Priority

**CRITICAL** - This is a fundamental architectural flaw that misrepresents the API capabilities and defeats the purpose of having a streaming interface.