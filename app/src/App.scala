package app

import com.twitter.finagle.Http
import com.twitter.util.Await

import cats.implicits._
import io.finch._
import io.finch.catsEffect._
import io.finch.circe._

import doobie._
import doobie.implicits._
import cats.effect.IO
import scala.concurrent.ExecutionContext

import io.finch.circe._
import io.circe.generic.auto._
import io.circe.Decoder, io.circe.Encoder, io.circe.generic.semiauto._
import io.circe._
import scala.util.Random

import zio._
import zio.blocking._
import zio.Runtime

import app.Person

object Main extends scala.App {

  val runtime = Runtime.default

  implicit val cs = IO.contextShift(ExecutionContext.global)
  val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql:app",
    "db_user",
    "db_pass"
  )

  def process(i: Int): ZIO[zio.blocking.Blocking, Throwable, Int] = {
    def randomFailure(prob: Int, max: Int = 10): Int = {
      val r = new Random()
      if (prob < r.nextInt(max)) prob
      else {
        println("crashed")
        throw new RuntimeException("failed")
      }
    }
    effectBlocking {
      println(s"started for $i")
      Thread.sleep(i * 1000)
      randomFailure(5)
      println(s"done for $i")
      i
    }.retryUntil(_ => false)
  }

  implicit val decoder: Decoder[Person] = deriveDecoder[Person]
  implicit val encoder: Encoder[Person] = deriveEncoder[Person]
  implicit val encodeExceptionCirce: Encoder[Exception] =
    Encoder.instance(e =>
      Json.obj(
        "message" -> Option(e.getMessage).fold(Json.Null)(Json.fromString)
      )
    )

  val listPerson: Endpoint[IO, List[Person]] =
    get("person") {
      sql"select name, age from person"
        .query[Person]
        .to[List]
        .transact(xa)
        .unsafeRunSync match {
        case p => Ok(p)
      }
    }

  val postPerson: Endpoint[IO, Person] =
    post("person" :: jsonBody[Person]) { p: Person =>
      try {
        sql"insert into person (uuid, name, age) values (${new Random()
          .nextInt(1000)
          .toString}, ${p.name}, ${p.age})".update.run
          .transact(xa)
          .unsafeRunSync match {
          case v if v > 0 => Created(Person(p.name, p.age))
          case _          => BadRequest(new Exception("bad request"))
        }
      } catch {
        case e: org.postgresql.util.PSQLException => BadRequest(e)
      }
    }
  val getPerson: Endpoint[IO, Person] =
    get("person" :: path[String]) { name: String =>
      sql"select name, age from person where name = ${name}"
        .query[Person]
        .option
        .transact(xa)
        .unsafeRunSync match {
        case Some(p) => Ok(p)
        case None =>
          NotFound(new Exception(s"Person ${name} not found"))
      }
    }
  val compute: Endpoint[IO, Int] =
    post("process" :: path[Int]) { v: Int =>
      runtime.unsafeRunAsync_(process(v))
      Ok(v)
    }

  Await.ready(
    Http.server.serve(
      ":8080",
      (listPerson :+: postPerson :+: getPerson :+: compute)
        .toServiceAs[Application.Json]
    )
  )
}
