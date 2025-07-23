package works.iterative.claude.internal

import cats.effect.IO
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class LoggingSetupTest extends CatsEffectSuite {

  test("Logger[IO] can be created and used") {
    val result = for {
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info("Test log message")
    } yield "success"

    result.map(assertEquals(_, "success"))
  }

  test("LoggingService can use injected logger") {
    val service = new LoggingService()
    service.logMessage("test message").map(assertEquals(_, "logged"))
  }
}

class LoggingService {
  def logMessage(message: String): IO[String] =
    for {
      logger <- Slf4jLogger.create[IO]
      _ <- logger.info(s"LoggingService: $message")
    } yield "logged"
}
