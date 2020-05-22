package example3

import cats.data.Kleisli
import cats.effect.{
  Async,
  Blocker,
  Bracket,
  Concurrent,
  ConcurrentEffect,
  ContextShift,
  ExitCode,
  IO,
  IOApp,
  Resource,
  Sync,
  Timer
}
import cats.implicits._
import cats.~>
import com.ovoenergy.effect.natchez.http4s.server.{ Configuration, TraceMiddleware }
import fs2.concurrent.Queue
import io.jaegertracing.Configuration.{ ReporterConfiguration, SamplerConfiguration }
import natchez._
import natchez.jaeger.Jaeger
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.{ HttpApp, HttpRoutes }

import scala.concurrent.duration._

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = (new Server).run[IO]
}

object doobie {

  trait Transactor[F[_]]

  def transact[M[_], A](xa: Transactor[M])(implicit ev: Bracket[M, Throwable]): fs2.Stream[M, A] = fs2.Stream.empty

  // type signature intentionally similar to doobie's newHikariTransactor
  def fakeTransactor[M[_]: Async: ContextShift](blocker: Blocker): Resource[M, Transactor[M]] =
    Resource.make(Sync[M].pure(new Transactor[M] {}))(_ => Sync[M].unit)
}

class Server {
  import doobie._

  type TraceF[F[_], A] = Kleisli[F, Span[F], A]

  def buildQueue[F[_]: Sync, G[_]: Concurrent, A]: F[Queue[G, A]] =
    Queue.in[F].unbounded[G, A]

  def routes[F[_]: Sync](endpoints: Endpoints[F]): HttpRoutes[F] =
    Router(
      "/foo" -> endpoints.foo.service,
    )

  def entryPointResource[F[_]: Sync]: Resource[F, EntryPoint[F]] =
    Jaeger.entryPoint[F]("scala") { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv).withReporter(ReporterConfiguration.fromEnv).getTracer
      }
    }

  def configuration[F[_]: Sync]: Configuration[F] = Configuration.default()

  // dummy transactor (in real code, this is from doobie)
  // you can use natchez-doobie to obtain a traced transactor in reality
  def getTransactor[F[_]: Async: ContextShift](ep: EntryPoint[F]): Resource[F, Transactor[TraceF[F, *]]] =
    for {
      b <- Blocker[F]
      span <- ep.continueOrElseRoot("transactor", Kernel(Map.empty))
      lower = λ[Kleisli[F, Span[F], *] ~> F](_(span))
      xa <- doobie.fakeTransactor[Kleisli[F, Span[F], *]](b).mapK(lower)
    } yield xa

  def app[F[_]: Sync: Timer](ep: EntryPoint[F], xa: Transactor[TraceF[F, *]], queue: Queue[TraceF[F, *], Int]): HttpApp[F] = {
    val fooRepo: FooRepo[Kleisli[F, Span[F], *]] = new FooRepo(xa, queue)
    val endpoints = new Endpoints(new FooHttpEndpoint(fooRepo))

    // here you're creating the routes with the effect type being Kleisli[F, Span[F], *] so you
    // then get the right type back to pass in the middleware & don't have to convert anything
    val baseApp: HttpApp[Kleisli[F, Span[F], *]] = routes(endpoints).orNotFound
    val traceMiddleware: HttpApp[Kleisli[F, Span[F], *]] => HttpApp[F] = TraceMiddleware[F](ep, configuration[F])
    traceMiddleware(baseApp)
  }

  def blazeServer[F[_]: ConcurrentEffect: Timer](app: HttpApp[F]): fs2.Stream[F, ExitCode] =
    BlazeServerBuilder[F].bindHttp(8888, "0.0.0.0").withHttpApp(app).serve

  def kafkaConsumer[F[_]: Sync, G[_]: ConcurrentEffect](queue: Queue[TraceF[G, *], Int]) = queue.dequeue

  def run[F[_]: ConcurrentEffect: ContextShift: Timer]: F[ExitCode] = {
    for {
      entryPoint <- fs2.Stream.resource(entryPointResource)
      xa <- fs2.Stream.resource(getTransactor(entryPoint))
      queue <- fs2.Stream.eval(buildQueue[F, TraceF[F, *], Int])

      blazeStream = blazeServer[F](app(entryPoint, xa, queue)).void
      kafkaModule = new KafkaModule(queue)
      /*
Error:(103, 21) Cannot find implicit value for ConcurrentEffect[[β$10$]cats.data.Kleisli[F,natchez.Span[F],β$10$]].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler, a ContextShift[[β$10$]cats.data.Kleisli[F,natchez.Span[F],β$10$]]
or some equivalent type.
       */
      kafkaProducerStream = kafkaModule.getProducerStream

      _ <- fs2.Stream(
        blazeStream,
        kafkaProducerStream
      ).parJoinUnbounded
    } yield ExitCode.Success
  }.compile.lastOrError
}

class KafkaModule[F[_]: ConcurrentEffect: ContextShift](queue: Queue[F, Int]) {
  // pretend this uses fs2-kafka, which requires ConcurrentEffect and ContextShift
  def getProducerStream: fs2.Stream[F, Int] = queue.dequeue
}

class Endpoints[F[_]](val foo: FooHttpEndpoint[F])

class FooRepo[F[_]: Bracket[*[_], Throwable]: Timer: Trace](xa: doobie.Transactor[F], queue: Queue[F, Int]) {

  def findAll(): fs2.Stream[F, Int] =
    fs2.Stream.sleep_(1.second) *> {
      doobie.transact[F, Int](xa) ++ fs2.Stream.emits(Seq(1, 2, 3, 4, 5))
    }
}

class FooHttpEndpoint[F[_]: Sync: Trace: Timer](repo: FooRepo[F]) extends Http4sDsl[F] {

  val service: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      Trace[F].put("foo" -> TraceValue.boolToTraceValue(true)) *>
        Timer[F].sleep(100.millis) *>
        Trace[F].span("responding") {
          Trace[F]
            .span("db:FooRepo/findAll") {
              repo.findAll().compile.toList
            }
            .flatMap { seq =>
              Trace[F].span("processing db records") {
                Timer[F].sleep(200.millis) *> Ok(seq.mkString(","))
              }
            }
        }
  }
}
