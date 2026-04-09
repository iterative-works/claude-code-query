// PURPOSE: Domain model for sub-agent metadata read from .meta.json sidecar files
// PURPOSE: Captures identity, classification, and transcript path for a sub-agent session

package works.iterative.claude.core.log.model

case class SubAgentMetadata(
    agentId: String,
    agentType: Option[String],
    description: Option[String],
    transcriptPath: os.Path
)
