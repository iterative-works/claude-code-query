// PURPOSE: Spike: minimal cc-chat custom element measuring the Scala.js bundle floor.
// PURPOSE: Registers <cc-chat> with a guarded define and renders a static placeholder.
package works.iterative.claude.uispike.bare

import org.scalajs.dom
import scala.scalajs.js

/** Minimal custom element: static placeholder content, no model, no rendering library. */
class CcChatElement extends dom.HTMLElement:
  def connectedCallback(): Unit =
    val root = dom.document.createElement("div")
    root.textContent = "cc-chat (bare spike)"
    appendChild(root)

object CcChatBare:
  def main(args: Array[String]): Unit =
    // scala-js-dom's CustomElementRegistry facade lacks `get`, so the guard goes through js.Dynamic
    val registry = dom.window.customElements
    if js.isUndefined(registry.asInstanceOf[js.Dynamic].get("cc-chat")) then
      registry.define("cc-chat", js.constructorOf[CcChatElement])
