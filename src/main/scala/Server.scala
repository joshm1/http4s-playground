import java.util.concurrent.atomic.AtomicInteger

import cats.data.{Kleisli, OptionT}
import cats.effect.{Bracket, ConcurrentEffect, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
import cats.~>
import com.ovoenergy.effect.natchez.http4s.server.{Configuration, TraceMiddleware}
import io.jaegertracing.Configuration.{ReporterConfiguration, SamplerConfiguration}
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{HttpApp, HttpRoutes}

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = (new Server).run[IO]
}

class Server {

  def _routesWorkingExample[F[_]: Sync]: HttpRoutes[F] = {
    object dsl extends Http4sDsl[F];
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name => Ok(name)
    }
  }

  def _routesBetterExampleOfRealUseCase[F[_]: Sync](endpoints: Endpoints[F]): HttpRoutes[F] =
    Router(
      "/foo" -> endpoints.foo.service,
      "/bar" -> endpoints.bar.service,
    )

  def entryPointResource[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("natchez-example") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
          .withReporter(ReporterConfiguration.fromEnv)
          .getTracer
      }
    }

  def configuration[F[_]: Sync]: Configuration[F] = Configuration.default()

  // dummy transactor (in real code, this is from doobie)
  // you can use natchez-doobie to obtain a traced transactor in reality
  def getTransactor[F[_]: Sync]: Resource[F, Transactor[Kleisli[F, Span[F], *]]] =
    Resource.make(Sync[F].pure(new Transactor[Kleisli[F, Span[F], *]] {}))(_ => Sync[F].unit)

  def getEndpoints[F[_]: Sync: Trace](xa: Transactor[F]): Endpoints[F] =
    new Endpoints[F](
      new FooHttpEndpoint[F](xa),
      new BarHttpEndpoint[F](xa)
    )

  def app[F[_]: Sync: Bracket[*[_], Throwable]](ep: EntryPoint[F], xa: Transactor[Kleisli[F, Span[F], *]]): HttpApp[F] = {
    val endpoints = getEndpoints(xa)

    // here you're creating the routes with the effect type being Kleisli[F, Span[F], *] so you
    // then get the right type back to pass in the middleware & don't have to convert anything
    val baseApp: HttpApp[Kleisli[F, Span[F], *]] = _routesBetterExampleOfRealUseCase(endpoints).orNotFound
    val traceMiddleware: HttpApp[Kleisli[F, Span[F], *]] => HttpApp[F] = TraceMiddleware[F](ep, configuration[F])
    traceMiddleware(baseApp)
  }

  def server[F[_]: ConcurrentEffect: Timer](app: HttpApp[F]): fs2.Stream[F, ExitCode] =
    BlazeServerBuilder[F].bindHttp(8888, "0.0.0.0").withHttpApp(app).serve

  def run[F[_]: ConcurrentEffect: Timer]: F[ExitCode] = {
    for {
      entryPoint <- fs2.Stream.resource(entryPointResource)
      xa <- fs2.Stream.resource(getTransactor)
      exitCode <- server[F](app(entryPoint, xa))
    } yield exitCode
  }.compile.lastOrError
}

trait Transactor[F[_]] {
  def test(implicit ev: Sync[F]): F[Int] = Sync[F].pure(1)
}

class Endpoints[F[_]](
    val foo: FooHttpEndpoint[F],
    val bar: BarHttpEndpoint[F]
)

class FooHttpEndpoint[F[_]: Sync](xa: Transactor[F]) extends Http4sDsl[F] {

  val service: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> / => xa.test.flatMap(i => Ok(s"foo ${i}"))
  }
}

// this may work, but would require a ton of work across the applications to change the way dependencies
// are injected across all of my classes (hoping not to have to do this initially), and is a bit messier
class DifferentFooHttpEndpoint {

  def service[F[_]: Sync](xa: Transactor[F]): HttpRoutes[F] = {
    object dsl extends Http4sDsl[F]
    import dsl._
    HttpRoutes.of[F] {
      case GET -> / => xa.test.flatMap(i => Ok(s"fixed foo ${i}"))
    }
  }
}

class BarHttpEndpoint[F[_]: Sync](xa: Transactor[F]) extends Http4sDsl[F] {

  val service: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> / => xa.test.flatMap(i => Ok(s"bar ${i}"))
  }
}
