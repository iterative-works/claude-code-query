# Phase 03 Tasks: Direct API - Basic session lifecycle

**Issue:** CC-15
**Phase Context:** phase-03-context.md

## Tasks

### Setup

- [x] [setup] Review existing ProcessManager, MockCliScript, and ClaudeCode patterns for reuse

### Tests First (TDD)

- [x] [test] Session trait compilation test - verify trait compiles with expected method signatures
- [x] [test] SDKUserMessage stdin encoding test - verify JSON written to stdin matches expected format with session ID
- [x] [test] Session ID extraction test - extract session_id from SystemMessage init
- [x] [test] Session ID pending default test - defaults to "pending" when no init received
- [x] [test] ResultMessage end-of-flow test - Flow emits up to and including ResultMessage then completes
- [x] [test] Session ID update from ResultMessage test - sessionId updated after send completes
- [x] [test] Close terminates process test - verify close destroys process

### Implementation

- [x] [impl] Create Session trait with send, close, sessionId methods
- [x] [impl] Create SessionProcess - process startup, stdin/stdout management, stderr capture
- [x] [impl] Implement session ID extraction from init SystemMessage
- [x] [impl] Implement send() - write SDKUserMessage to stdin, return Flow[Message] up to ResultMessage
- [x] [impl] Implement close() - close stdin, wait, destroy if needed
- [x] [impl] Add ClaudeCode.session factory methods (instance and companion)
- [x] [impl] Add Session and SessionOptions re-exports to package.scala

### Integration

- [x] [integ] Create SessionMockCliScript for session protocol simulation
- [x] [integ] Integration test: full single-turn session lifecycle with mock CLI
- [x] [integ] Integration test: mock CLI receives correct stdin JSON
- [x] [integ] Integration test: session extracts session ID from init message
- [x] [integ] Integration test: KeepAlive and StreamEvent messages emitted in Flow
- [x] [integ] Integration test: ClaudeCode.session factory creates working session
- [x] [integ] E2E test: real CLI session with single turn (gated on CLI availability)
- [x] [integ] E2E test: session ID is valid non-pending after first turn
- [x] [integ] Verify all existing tests still pass (no regressions)
