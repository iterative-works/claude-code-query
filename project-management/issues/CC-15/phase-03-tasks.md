# Phase 03 Tasks: Direct API - Basic session lifecycle

**Issue:** CC-15
**Phase Context:** phase-03-context.md

## Tasks

### Setup

- [ ] [setup] Review existing ProcessManager, MockCliScript, and ClaudeCode patterns for reuse

### Tests First (TDD)

- [ ] [test] Session trait compilation test - verify trait compiles with expected method signatures
- [ ] [test] SDKUserMessage stdin encoding test - verify JSON written to stdin matches expected format with session ID
- [ ] [test] Session ID extraction test - extract session_id from SystemMessage init
- [ ] [test] Session ID pending default test - defaults to "pending" when no init received
- [ ] [test] ResultMessage end-of-flow test - Flow emits up to and including ResultMessage then completes
- [ ] [test] Session ID update from ResultMessage test - sessionId updated after send completes
- [ ] [test] Close terminates process test - verify close destroys process

### Implementation

- [ ] [impl] Create Session trait with send, close, sessionId methods
- [ ] [impl] Create SessionProcess - process startup, stdin/stdout management, stderr capture
- [ ] [impl] Implement session ID extraction from init SystemMessage
- [ ] [impl] Implement send() - write SDKUserMessage to stdin, return Flow[Message] up to ResultMessage
- [ ] [impl] Implement close() - close stdin, wait, destroy if needed
- [ ] [impl] Add ClaudeCode.session factory methods (instance and companion)
- [ ] [impl] Add Session and SessionOptions re-exports to package.scala

### Integration

- [ ] [integ] Create SessionMockCliScript for session protocol simulation
- [ ] [integ] Integration test: full single-turn session lifecycle with mock CLI
- [ ] [integ] Integration test: mock CLI receives correct stdin JSON
- [ ] [integ] Integration test: session extracts session ID from init message
- [ ] [integ] Integration test: KeepAlive and StreamEvent messages emitted in Flow
- [ ] [integ] Integration test: ClaudeCode.session factory creates working session
- [ ] [integ] E2E test: real CLI session with single turn (gated on CLI availability)
- [ ] [integ] E2E test: session ID is valid non-pending after first turn
- [ ] [integ] Verify all existing tests still pass (no regressions)
