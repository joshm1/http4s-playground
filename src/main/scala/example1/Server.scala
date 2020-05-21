package example1

import cats.data.Kleisli
import cats.effect.{ConcurrentEffect, ExitCode, IO, IOApp, Resource, Sync, Timer}
import cats.implicits._
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

  def routes[F[_]: Sync](endpoints: Endpoints[F]): HttpRoutes[F] =
    Router(
      "/foo" -> endpoints.foo.service,
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

  def getEndpoints[F[_]: Sync](xa: Transactor[F]): Endpoints[F] =
    new Endpoints[F](
      new FooHttpEndpoint[F](xa)
    )

  def app[F[_]: Sync](ep: EntryPoint[F], xa: Transactor[Kleisli[F, Span[F], *]]): HttpApp[F] = {
    val endpoints = getEndpoints(xa)

    // here you're creating the routes with the effect type being Kleisli[F, Span[F], *] so you
    // then get the right type back to pass in the middleware & don't have to convert anything
    val baseApp: HttpApp[Kleisli[F, Span[F], *]] = routes(endpoints).orNotFound
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
    val foo: FooHttpEndpoint[F]
)

class FooHttpEndpoint[F[_]: Sync](xa: Transactor[F]) extends Http4sDsl[F] {

  val service: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> / => xa.test.flatMap(i => Ok(s"foo ${i}"))
  }
}
