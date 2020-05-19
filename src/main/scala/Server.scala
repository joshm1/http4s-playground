import cats.data.Kleisli
import cats.effect.{ConcurrentEffect, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import com.ovoenergy.effect.natchez.http4s.server.{Configuration, TraceMiddleware}
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.implicits._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = new Server[IO].run
}

class Server[F[_] : ConcurrentEffect : Timer] extends Http4sDsl[F] {

  def routes: HttpRoutes[F] = Router(
    "/foo" -> HttpRoutes.of[F] { case POST -> Root => Ok("foo") }
  )

  def entryPointResource: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("natchez-example") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv).withReporter(ReporterConfiguration.fromEnv).getTracer
      }
    }

  def configuration: Configuration[F] = Configuration.default()

  def run: F[ExitCode] = {
    for {
      entryPoint <- fs2.Stream.resource(entryPointResource)
      httpApp = {
        val baseApp: HttpApp[F] = routes.orNotFound

        // does not work, `baseApp` is the wrong type. how to get HttpApp[F] to a HttpApp[Kleisli[F, Span[F], *]] so
        // it can be passed to traceMiddleware?
        val traceMiddleware: HttpApp[Kleisli[F, Span[F], *]] => HttpApp[F] =
          TraceMiddleware.apply[F](entryPoint, configuration)
        // traceMiddleware(baseApp)

        baseApp
      }

      exitCode <- BlazeServerBuilder[F].bindHttp(8888, "0.0.0.0").withHttpApp(httpApp).serve
    } yield exitCode
  }.compile.lastOrError
}
