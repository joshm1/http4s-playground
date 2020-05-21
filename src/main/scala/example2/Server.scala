package example2

import cats.data.Kleisli
import cats.effect.{Async, Blocker, Bracket, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
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

object doobie {

  trait Transactor[F[_]] {
    def trans
  }

  def transact[M[_], A](xa: Transactor[M])(implicit ev: Bracket[M, Throwable]): fs2.Stream[M, A] = fs2.Stream.emit(???)

  // type signature intentionally similar to doobie's newHikariTransactor
  def fakeTransactor[M[_]: Async: ContextShift](blocker:         Blocker): Resource[M, Transactor[M]] = ???
}

class Server {
  import doobie._

  def routes[F[_]: Sync](endpoints: Endpoints[F]): HttpRoutes[F] =
    Router(
      "/foo" -> endpoints.foo.service,
    )

  def entryPointResource[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("natchez-example") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv).withReporter(ReporterConfiguration.fromEnv).getTracer
      }
    }

  def configuration[F[_]: Sync]: Configuration[F] = Configuration.default()

  // dummy transactor (in real code, this is from doobie)
  // you can use natchez-doobie to obtain a traced transactor in reality
  def getTransactor[F[_]: Async: ContextShift]: Resource[F, Transactor[Kleisli[F, Span[F], *]]] =
    for {
      b <- Blocker[F]
      xa <- doobie.fakeTransactor[Kleisli[F, Span[F], *]](b)
      /*
      Error:(54, 10) type mismatch;
      found   : cats.effect.Resource[[x]cats.data.Kleisli[F,natchez.Span[F],x],example2.doobie.Transactor[[γ$1$]cats.data.Kleisli[F,natchez.Span[F],γ$1$]]]
      required: cats.effect.Resource[[_]F[_],example2.doobie.Transactor[[γ$0$]cats.data.Kleisli[F,natchez.Span[F],γ$0$]]]
       */
    } yield xa

  def app[F[_]: Sync](ep: EntryPoint[F], xa: Transactor[Kleisli[F, Span[F], *]]): HttpApp[F] = {
    val fooRepo: FooRepo[Kleisli[F, Span[F], *]] = new FooRepo[F](xa)
    /*
    Error:(58, 67) type mismatch;
    found   : example2.doobie.Transactor[[γ$2$]cats.data.Kleisli[F,natchez.Span[F],γ$2$]]
    required: example2.doobie.Transactor[F]
    Error:(58, 52) type mismatch;
    found   : example2.FooRepo[F]
    required: example2.FooRepo[[γ$3$]cats.data.Kleisli[F,natchez.Span[F],γ$3$]]
     */
    val endpoints = new Endpoints[F](new FooHttpEndpoint[F](fooRepo))
    /*
    Error:(59, 61) type mismatch;
    found   : example2.FooRepo[[γ$3$]cats.data.Kleisli[F,natchez.Span[F],γ$3$]]
    required: example2.FooRepo[F]
    val endpoints = new Endpoints[F](new FooHttpEndpoint[F](fooRepo))
     */

    // here you're creating the routes with the effect type being Kleisli[F, Span[F], *] so you
    // then get the right type back to pass in the middleware & don't have to convert anything
    val baseApp: HttpApp[Kleisli[F, Span[F], *]] = routes(endpoints).orNotFound
    /*
 Error:(63, 70) type mismatch;
 found   : cats.data.Kleisli[[_]F[_],org.http4s.Request[[_]F[_]],org.http4s.Response[[_]F[_]]]
 required: org.http4s.HttpApp[[γ$4$]cats.data.Kleisli[F,natchez.Span[F],γ$4$]]
    (which expands to)  cats.data.Kleisli[[γ$4$]cats.data.Kleisli[F,natchez.Span[F],γ$4$],org.http4s.Request[[γ$4$]cats.data.Kleisli[F,natchez.Span[F],γ$4$]],org.http4s.Response[[γ$4$]cats.data.Kleisli[F,natchez.Span[F],γ$4$]]]
     */
    val traceMiddleware: HttpApp[Kleisli[F, Span[F], *]] => HttpApp[F] = TraceMiddleware[F](ep, configuration[F])
    traceMiddleware(baseApp)
  }

  def server[F[_]: ConcurrentEffect: Timer](app: HttpApp[F]): fs2.Stream[F, ExitCode] =
    BlazeServerBuilder[F].bindHttp(8888, "0.0.0.0").withHttpApp(app).serve

  def run[F[_]: ConcurrentEffect: ContextShift: Timer]: F[ExitCode] = {
    for {
      entryPoint <- fs2.Stream.resource(entryPointResource)
      xa <- fs2.Stream.resource(getTransactor)
      exitCode <- server[F](app(entryPoint, xa))
    } yield exitCode
  }.compile.lastOrError
}

class Endpoints[F[_]](val foo: FooHttpEndpoint[F])

class FooRepo[F[_]: Bracket[*[_], Throwable]](xa: doobie.Transactor[F]) {
  def findAll(): fs2.Stream[F, Int] = doobie.transact[F, Int](xa)
}

class FooHttpEndpoint[F[_]: Sync](repo: FooRepo[F]) extends Http4sDsl[F] {

  val service: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> / =>
      Ok(repo.findAll().compile.toList.map(_.mkString(",")))
  }
}