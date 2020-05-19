import cats.effect._
import cats.implicits._
import io.jaegertracing.Configuration._
import natchez._
import natchez.http4s.implicits._
import natchez.jaeger.Jaeger
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server._
import org.http4s.server.blaze.BlazeServerBuilder

object Http4sExample extends IOApp {

  // This is what we want to write: routes in F[_]: ...: Trace
  def routes[F[_]: Sync: Trace]: HttpRoutes[F] = {
    object dsl extends Http4sDsl[F]; import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        Trace[F].put("woot" -> 42) *>
          Trace[F].span("responding") {
            Ok(s"Hello, $name.")
          }
    }
  }

  // Normal constructor for an HttpApp in F *without* a Trace constraint.
  def app[F[_]: Sync: Bracket[?[_], Throwable]](ep: EntryPoint[F]): HttpApp[F] =
    Router("/" -> ep.liftT(routes)).orNotFound // <-- Lifted routes

  // Normal server resource
  def server[F[_]: ConcurrentEffect: Timer](routes: HttpApp[F]): Resource[F, Server[F]] =
    BlazeServerBuilder[F]
      .bindHttp(8080, "localhost")
      .withHttpApp(routes)
      .resource

  // Normal EntryPoint resource
  def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("natchez-example") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  // Main method instantiates F to IO
  def run(args: List[String]): IO[ExitCode] =
    entryPoint[IO].map(app(_)).flatMap(server(_)).use(_ => IO.never).as(ExitCode.Success)

}
