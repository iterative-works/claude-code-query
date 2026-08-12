// PURPOSE: Structured user input encoded as content blocks so verbatim text never entangles with context
// PURPOSE: Provides a pure codec whose decode inverts encode by construction — decode(encode(i)) == Some(i)

package works.iterative.claude.core.model

import io.circe.{Json, parser}

/** A user message plus the context and delivery channel that accompany it.
  *
  * `text` is the human's verbatim message. `context` is per-message ambient
  * information (what the user is viewing, named instruction blocks, …).
  * `channel` records how the input reached the session.
  */
case class UserInput(
    text: String,
    context: List[ContextItem] = Nil,
    channel: Channel = Channel.Interactive
)

/** A single piece of context attached to a [[UserInput]]. */
enum ContextItem:
  /** A resource the user is currently looking at. */
  case Viewing(url: String)

  /** A named value — a matter-instructions block, an email envelope, … */
  case Labeled(name: String, value: String)

/** How a [[UserInput]] reached the session. Open vocabulary: `Custom` carries
  * any channel name we have not given a dedicated case, so the enum is never
  * closed against a new delivery path.
  */
enum Channel:
  /** A person typing into an interactive session. */
  case Interactive

  /** A system-generated prompt reacting to an event. */
  case Reactive

  /** Any other channel, named verbatim. */
  case Custom(name: String)

object UserInput:

  // Single namespaced key that marks a text block as library-generated metadata
  // rather than user text. User text is identified by POSITION (the final block),
  // never by this marker, so text that mimics a metadata block still round-trips.
  private val MetaKey = "claude_code_query_meta"

  /** Encodes user input as the wire content: a list of text blocks. Every
    * metadata item — the channel and each context item — is its own block
    * carrying library-generated JSON; the verbatim user text is the final
    * block, alone. The CLI accepts this array and the transcript preserves the
    * list, so the structure survives the round trip untouched (no escaping, no
    * grammar over the user's text).
    */
  def encode(input: UserInput): List[TextBlock] =
    val channelBlock = TextBlock(channelMetaText(input.channel))
    val contextBlocks =
      input.context.map(item => TextBlock(contextMetaText(item)))
    (channelBlock :: contextBlocks) :+ TextBlock(input.text)

  /** Inverts [[encode]] from the transcript's user-entry content, which the log
    * parser represents as `List[ContentBlock]` (a plain-string legacy entry
    * arrives already wrapped as a single `TextBlock`, and a raw wire string is
    * the `String` arm).
    *
    * Returns `Some` only for content this codec produced: the first block is a
    * channel-metadata block, every middle block is a context-metadata block,
    * and the final block is a text block holding the verbatim user text.
    * Anything else — a bare legacy entry, a foreign block list, a plain string
    * — returns `None`, because `encode` never produces it.
    */
  def decode(content: List[ContentBlock] | String): Option[UserInput] =
    content match
      case _: String                             => None
      case blocks: List[ContentBlock @unchecked] => decodeBlocks(blocks)

  private def decodeBlocks(blocks: List[ContentBlock]): Option[UserInput] =
    blocks match
      case Nil => None
      case _   =>
        for
          userText <- asText(blocks.last)
          metaTexts <- traverseText(blocks.init)
          channelAndContext <- decodeMeta(metaTexts)
        yield UserInput(userText, channelAndContext._2, channelAndContext._1)

  private def decodeMeta(
      texts: List[String]
  ): Option[(Channel, List[ContextItem])] =
    texts match
      case Nil => None // encode always emits a channel block first
      case channelText :: contextTexts =>
        for
          channel <- parseChannelMeta(channelText)
          items <- traverse(contextTexts)(parseContextMeta)
        yield (channel, items)

  private def asText(block: ContentBlock): Option[String] =
    block match
      case TextBlock(text) => Some(text)
      case _               => None

  private def traverseText(blocks: List[ContentBlock]): Option[List[String]] =
    traverse(blocks)(asText)

  private def traverse[A, B](as: List[A])(f: A => Option[B]): Option[List[B]] =
    as.foldRight(Option(List.empty[B])): (a, acc) =>
      for
        b <- f(a)
        rest <- acc
      yield b :: rest

  // --- metadata block encoding ---

  private def channelMetaText(channel: Channel): String =
    metaText("channel", "channel", channelJson(channel))

  private def contextMetaText(item: ContextItem): String =
    metaText("context", "item", contextItemJson(item))

  private def metaText(
      role: String,
      payloadKey: String,
      payload: Json
  ): String =
    Json
      .obj(
        MetaKey -> Json.obj(
          "role" -> Json.fromString(role),
          payloadKey -> payload
        )
      )
      .noSpaces

  private def channelJson(channel: Channel): Json =
    channel match
      case Channel.Interactive =>
        Json.obj("kind" -> Json.fromString("interactive"))
      case Channel.Reactive =>
        Json.obj("kind" -> Json.fromString("reactive"))
      case Channel.Custom(name) =>
        Json.obj(
          "kind" -> Json.fromString("custom"),
          "name" -> Json.fromString(name)
        )

  private def contextItemJson(item: ContextItem): Json =
    item match
      case ContextItem.Viewing(url) =>
        Json.obj(
          "kind" -> Json.fromString("viewing"),
          "url" -> Json.fromString(url)
        )
      case ContextItem.Labeled(name, value) =>
        Json.obj(
          "kind" -> Json.fromString("labeled"),
          "name" -> Json.fromString(name),
          "value" -> Json.fromString(value)
        )

  // --- metadata block decoding ---

  private def parseChannelMeta(text: String): Option[Channel] =
    metaPayload(text, role = "channel", payloadKey = "channel")
      .flatMap(channelFromJson)

  private def parseContextMeta(text: String): Option[ContextItem] =
    metaPayload(text, role = "context", payloadKey = "item")
      .flatMap(contextItemFromJson)

  private def metaPayload(
      text: String,
      role: String,
      payloadKey: String
  ): Option[Json] =
    parser
      .parse(text)
      .toOption
      .flatMap: json =>
        val meta = json.hcursor.downField(MetaKey)
        val roleMatches = meta.get[String]("role").toOption.contains(role)
        if roleMatches then meta.get[Json](payloadKey).toOption else None

  private def channelFromJson(json: Json): Option[Channel] =
    val cursor = json.hcursor
    cursor
      .get[String]("kind")
      .toOption
      .flatMap:
        case "interactive" => Some(Channel.Interactive)
        case "reactive"    => Some(Channel.Reactive)
        case "custom"      =>
          cursor.get[String]("name").toOption.map(Channel.Custom.apply)
        case _ => None

  private def contextItemFromJson(json: Json): Option[ContextItem] =
    val cursor = json.hcursor
    cursor
      .get[String]("kind")
      .toOption
      .flatMap:
        case "viewing" =>
          cursor.get[String]("url").toOption.map(ContextItem.Viewing.apply)
        case "labeled" =>
          for
            name <- cursor.get[String]("name").toOption
            value <- cursor.get[String]("value").toOption
          yield ContextItem.Labeled(name, value)
        case _ => None
