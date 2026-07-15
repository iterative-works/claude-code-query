package works.iterative.claude.core.model

// PURPOSE: Latency measurements carried on a result message, in milliseconds
// PURPOSE: Time to first token, to first stream event, and to the first API request

case class ResultTimings(
    ttftMs: Option[Int] = None,
    ttftStreamMs: Option[Int] = None,
    timeToRequestMs: Option[Int] = None
)
