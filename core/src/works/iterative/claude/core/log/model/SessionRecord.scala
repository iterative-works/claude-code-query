// PURPOSE: Located handle to a session's vendor record tree on disk
// PURPOSE: Names the main transcript file and the directory holding sub-agent sidechains

package works.iterative.claude.core.log.model

/** A located session record.
  *
  * @param sessionId
  *   the vendor session id
  * @param mainTranscript
  *   the `<sessionId>.jsonl` main-thread transcript file
  * @param treeDir
  *   the `<sessionId>/` directory holding `subagents/`, `tool-results/`, and
  *   `workflows/`; may not exist when a session spawned no sub-agents
  */
case class SessionRecord(
    sessionId: String,
    mainTranscript: os.Path,
    treeDir: os.Path
)
