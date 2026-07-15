// PURPOSE: Identifier of a wire message — the vendor-issued `uuid` field
// PURPOSE: Join key between live stream messages and transcript entries

package works.iterative.claude.core.model

opaque type MessageId = String

object MessageId:
  def apply(value: String): MessageId = value

  extension (id: MessageId) def value: String = id
