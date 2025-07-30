# TODO: Fix Fake Streaming in Direct ClaudeCode API

## Phase 1: Investigation and Setup

- [x] **Analyze Issue** - Understand the fake streaming problem in direct implementation
- [x] **Create Implementation Plan** - Document approach and architecture changes
- [x] **Research Ox Flow APIs** - Get documentation on Flow.usingEmit and streaming patterns
- [ ] **Study Existing Tests** - Understand current test patterns for streaming behavior

## Phase 2: TDD Implementation - Core Streaming

### Test 1: Basic Streaming Integration Test
- [x] **Write Failing Test** - Test that messages arrive from CLI process as stream
- [x] **Commit Failing Test** - "Add failing test for real streaming in direct ClaudeCode"
- [x] **Implement Streaming** - Replace Flow.fromIterable with Flow.usingEmit
- [x] **Make Test Pass** - Implement minimum streaming functionality
- [x] **Commit Implementation** - "Implement real streaming for direct ClaudeCode query method"

### Test 2: Early Message Access Test  
- [x] **Write Failing Test** - Test messages arrive before process completion
- [x] **Commit Failing Test** - "Add failing test for early message access during streaming"
- [x] **Implement Feature** - Ensure messages emit as soon as available
- [x] **Make Test Pass** - Verify streaming doesn't wait for process completion
- [x] **Commit Implementation** - "Implement early message access in streaming"

### Test 3: Process Lifecycle Management
- [ ] **Write Failing Test** - Test proper process startup and cleanup
- [ ] **Commit Failing Test** - "Add failing test for process lifecycle during streaming"
- [ ] **Implement Feature** - Add proper process management in streaming
- [ ] **Make Test Pass** - Ensure processes start/cleanup correctly
- [ ] **Commit Implementation** - "Implement process lifecycle management for streaming"

## Phase 3: TDD Implementation - Error Handling

### Test 4: Process Error Handling
- [ ] **Write Failing Test** - Test process failures propagate correctly during streaming
- [ ] **Commit Failing Test** - "Add failing test for process error handling in streaming"
- [ ] **Implement Feature** - Add error propagation during streaming
- [ ] **Make Test Pass** - Ensure process errors break stream correctly
- [ ] **Commit Implementation** - "Implement process error handling during streaming"

### Test 5: Stderr Capture During Streaming
- [ ] **Write Failing Test** - Test stderr is captured and reported during streaming
- [ ] **Commit Failing Test** - "Add failing test for stderr capture during streaming"
- [ ] **Implement Feature** - Add concurrent stderr capture
- [ ] **Make Test Pass** - Ensure stderr is available for error reporting
- [ ] **Commit Implementation** - "Implement stderr capture for streaming processes"

## Phase 4: TDD Implementation - Advanced Features

### Test 6: Flow Early Termination
- [ ] **Write Failing Test** - Test Flow can be cancelled before process completion
- [ ] **Commit Failing Test** - "Add failing test for Flow early termination"
- [ ] **Implement Feature** - Add cancellation support to streaming
- [ ] **Make Test Pass** - Ensure processes cleanup when Flow terminates early
- [ ] **Commit Implementation** - "Implement Flow cancellation and cleanup"

### Test 7: Timeout Handling During Streaming
- [ ] **Write Failing Test** - Test timeout works correctly during streaming
- [ ] **Commit Failing Test** - "Add failing test for streaming timeout handling"
- [ ] **Implement Feature** - Add timeout support to streaming operations
- [ ] **Make Test Pass** - Ensure timeouts work during streaming
- [ ] **Commit Implementation** - "Implement timeout handling for streaming operations"

### Test 8: Backpressure and Slow Consumers
- [ ] **Write Failing Test** - Test streaming handles slow message consumers
- [ ] **Commit Failing Test** - "Add failing test for backpressure in streaming"
- [ ] **Implement Feature** - Add backpressure handling to streaming
- [ ] **Make Test Pass** - Ensure streaming doesn't overwhelm slow consumers
- [ ] **Commit Implementation** - "Implement backpressure handling in streaming"

## Phase 5: Integration and Verification

### Existing Test Compatibility
- [ ] **Run Existing Tests** - Verify existing direct implementation tests still pass
- [ ] **Fix Broken Tests** - Update any tests that assume blocking behavior
- [ ] **Commit Test Fixes** - "Update existing tests for new streaming implementation"

### Performance and Resource Testing
- [ ] **Write Resource Test** - Test memory usage doesn't grow with long streams
- [ ] **Write Performance Test** - Test streaming performs better than fake streaming
- [ ] **Verify Memory Management** - Ensure no memory leaks in streaming
- [ ] **Commit Performance Tests** - "Add performance and resource tests for streaming"

### Final Integration
- [ ] **Test Full User Scenarios** - End-to-end testing with real Claude CLI
- [ ] **Update Documentation** - Document new streaming behavior
- [ ] **Final Testing** - Comprehensive testing of all streaming features
- [ ] **Final Commit** - "Complete real streaming implementation for direct ClaudeCode"

## Success Verification

- [ ] **Verify Real Streaming** - Messages emit as CLI produces them
- [ ] **Verify Non-blocking** - Process starts immediately, doesn't wait for completion
- [ ] **Verify Early Access** - First messages available before CLI finishes
- [ ] **Verify Cancellation** - Flow terminates without waiting for process
- [ ] **Verify Resource Management** - Proper cleanup of processes and streams
- [ ] **Verify Error Handling** - Appropriate error propagation during streaming
- [ ] **Verify API Compatibility** - Existing query() method signature unchanged
- [ ] **Verify Performance** - No memory buildup from holding complete message lists

## Notes

- Follow TDD Red-Green-Commit cycle strictly
- Each test should call real interfaces with `???` stubs, not mocks
- Focus on top-down approach - test complete user scenarios first
- Mock only external dependencies (CLI executables, file system)
- Ensure each commit has a single clear purpose
- Keep existing effectful implementation unchanged (it already works correctly)