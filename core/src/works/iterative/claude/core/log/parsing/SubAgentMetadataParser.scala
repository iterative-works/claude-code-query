// PURPOSE: Pure parser for sub-agent .meta.json sidecar files into SubAgentMetadata
// PURPOSE: Returns None for missing required fields or unparseable input

package works.iterative.claude.core.log.parsing

import io.circe.Json
import works.iterative.claude.core.log.model.SubAgentMetadata

object SubAgentMetadataParser:

  def parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata] =
    val cursor = json.hcursor
    for agentId <- cursor.get[String]("agentId").toOption
    yield SubAgentMetadata(
      agentId = agentId,
      agentType = cursor.get[String]("agentType").toOption,
      description = cursor.get[String]("description").toOption,
      transcriptPath = transcriptPath
    )
