// PURPOSE: Spike: toy HTTP host proving the cc-chat delivery and wire contract end to end.
// PURPOSE: Serves the hashed element bundle from the classpath, a history page, and canned SSE.
package works.iterative.claude.uispike.host

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.util.concurrent.Executors

object SpikeHost:

  private def resourceBytes(path: String): Array[Byte] =
    val stream = getClass.getResourceAsStream(path)
    try stream.readAllBytes()
    finally stream.close()

  private lazy val bundleName: String =
    new String(resourceBytes("/assets/manifest.txt"), "UTF-8").trim

  private def indexHtml: String =
    s"""<!doctype html>
       |<html><head><meta charset="utf-8"><title>cc-chat spike</title></head>
       |<body style="font-family:sans-serif;margin:2rem;">
       |<h1>cc-chat spike host</h1>
       |<script type="module" src="/assets/$bundleName"></script>
       |<cc-chat history-url="/history" events-url="/events"
       |         style="display:block;max-width:60rem;"></cc-chat>
       |</body></html>
       |""".stripMargin

  private def respond(
      exchange: HttpExchange,
      status: Int,
      contentType: String,
      body: Array[Byte],
      extraHeaders: Map[String, String] = Map.empty
  ): Unit =
    exchange.getResponseHeaders.set("Content-Type", contentType)
    extraHeaders.foreach { (k, v) => exchange.getResponseHeaders.set(k, v) }
    exchange.sendResponseHeaders(status, body.length.toLong)
    val out = exchange.getResponseBody
    try
      out.write(body)
    finally exchange.close()

  private def historyBytes: Array[Byte] =
    sys.env.get("CC_CHAT_TRANSCRIPT").map(Path.of(_)).filter(Files.exists(_)) match
      case Some(path) => Files.readAllBytes(path)
      case None       => resourceBytes("/sample-history.jsonl")

  /** Canned live frames: a tool_use status, an assistant reply, its result (whose text must
    * NOT render again), then a result-only frame (whose text MUST render as the fallback).
    */
  private val liveFrames: List[(Int, String)] = List(
    600 -> """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/tmp/example"}}]},"session_id":"spike-session"}""",
    1200 -> """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Live reply: I read the file, all good."}]},"session_id":"spike-session"}""",
    600 -> """{"type":"result","subtype":"query","duration_ms":1500,"duration_api_ms":800,"is_error":false,"num_turns":1,"result":"Live reply: I read the file, all good.","session_id":"spike-session"}""",
    1500 -> """{"type":"result","subtype":"query","duration_ms":900,"duration_api_ms":500,"is_error":false,"num_turns":1,"result":"Fallback-only reply, rendered from the result frame.","session_id":"spike-session"}"""
  )

  private def serveEvents(exchange: HttpExchange): Unit =
    exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
    exchange.getResponseHeaders.set("Cache-Control", "no-cache")
    exchange.sendResponseHeaders(200, 0)
    val out = exchange.getResponseBody
    try
      liveFrames.foreach { (delayMs, json) =>
        Thread.sleep(delayMs.toLong)
        out.write(s"data: $json\n\n".getBytes("UTF-8"))
        out.flush()
      }
      // Keep the stream open so EventSource does not reconnect and replay the frames
      while true do
        Thread.sleep(15000)
        out.write(": keepalive\n\n".getBytes("UTF-8"))
        out.flush()
    catch case _: java.io.IOException => () // client went away
    finally exchange.close()

  def main(args: Array[String]): Unit =
    val port = sys.env.get("CC_CHAT_PORT").flatMap(_.toIntOption).getOrElse(8787)
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/assets/",
      exchange =>
        val name = exchange.getRequestURI.getPath.stripPrefix("/assets/")
        if name == bundleName then
          respond(
            exchange,
            200,
            "text/javascript",
            resourceBytes(s"/assets/$name"),
            Map("Cache-Control" -> "public, max-age=31536000, immutable")
          )
        else respond(exchange, 404, "text/plain", "not found".getBytes("UTF-8"))
    )
    server.createContext(
      "/history",
      exchange => respond(exchange, 200, "application/x-ndjson", historyBytes)
    )
    server.createContext("/events", serveEvents(_))
    server.createContext(
      "/",
      exchange => respond(exchange, 200, "text/html", indexHtml.getBytes("UTF-8"))
    )
    server.start()
    println(s"cc-chat spike host on http://localhost:$port serving /assets/$bundleName")
