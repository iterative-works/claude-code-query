// PURPOSE: Direct-style main API for Claude Code using Ox for structured concurrency
// PURPOSE: Provides streaming and sync query methods without cats-effect IO dependencies
package works.iterative.claude.direct

import ox.*
import ox.flow.Flow
import works.iterative.claude.core.model.*

object ClaudeCode:

  /** 
   * Executes a query and returns a Flow of messages from the Claude CLI.
   * Uses Ox direct-style programming for structured concurrency.
   */
  def query(options: QueryOptions): Flow[Message] = 
    ???