// PURPOSE: Pure parser for sub-agent .meta.json sidecar files into SubAgentMetadata
// PURPOSE: Returns None only for null JSON input; agentType and description are optional fields

package works.iterative.claude.core.log.parsing

import io.circe.Json
import works.iterative.claude.core.log.model.SubAgentMetadata

object SubAgentMetadataParser:

  def parse(json: Json, transcriptPath: os.Path): Option[SubAgentMetadata] =
    Option.when(!json.isNull):
      SubAgentMetadata(
        agentId = transcriptPath.last.stripSuffix(".jsonl"),
        agentType = json.hcursor.get[String]("agentType").toOption,
        description = json.hcursor.get[String]("description").toOption,
        transcriptPath = transcriptPath
      )
