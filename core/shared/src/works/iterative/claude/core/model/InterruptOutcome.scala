// PURPOSE: The vendor's answer to a control-channel interrupt — what queued input survived the stop
// PURPOSE: Built from the control_response's still_queued list correlated to the interrupt request

package works.iterative.claude.core.model

/** The outcome of an [[Session.interrupt]], read from the vendor's
  * `control_response`.
  *
  * `stillQueued` is the vendor's `still_queued` list: inputs that were queued
  * and survived the interrupt rather than being discarded with the stopped
  * turn.
  */
case class InterruptOutcome(stillQueued: List[String])
