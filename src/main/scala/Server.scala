import cats.data.Kleisli
import cats.effect.{Bracket, ConcurrentEffect, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.ovoenergy.effect.natchez.http4s.server.{Configuration, TraceMiddleware}
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes}

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = (new Server).run[IO]
}

class Server {

  def _routes[F[_] : Sync : Trace]: HttpRoutes[F] = {
    object dsl extends Http4sDsl[F];
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        Trace[F].put("woot" -> 42) *>
          Trace[F].span("responding") {
            Ok(s"Hello, $name.")
          }
    }
  }

  def routes[F[_] : Bracket[*[_], Throwable]](
                                               implicit ev: Sync[Kleisli[F, Span[F], *]])
  : HttpRoutes[Kleisli[F, Span[F], *]] = _routes

  def entryPointResource[F[_] : Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("natchez-example") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  def configuration[F[_] : Sync]: Configuration[F] = Configuration.default()

  def app[F[_] : Sync : Bracket[*[_], Throwable]](ep: EntryPoint[F]): HttpApp[F] = {
    val baseApp = routes.orNotFound
    val traceMiddleware: HttpApp[Kleisli[F, Span[F], *]] => HttpApp[F] = TraceMiddleware[F](ep, configuration[F])
    traceMiddleware(baseApp)
  }

  def server[F[_] : ConcurrentEffect : Timer](app: HttpApp[F]): fs2.Stream[F, ExitCode] =
    BlazeServerBuilder[F].bindHttp(8888, "0.0.0.0").withHttpApp(app).serve

  def run[F[_] : ConcurrentEffect : Bracket[*[_], Throwable] : Timer]
  : F[ExitCode] = {
    for {
      entryPoint <- fs2.Stream.resource(entryPointResource)
      exitCode <- server[F](app(entryPoint))
    } yield exitCode
    }.compile.lastOrError
}
