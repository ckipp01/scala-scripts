// using scala 2.13
// using option -Ywarn-unused
//
// Little quick and dirty script to grab my waka time stats every day and put
// them in Mongo.

import $dep.`org.mongodb.scala::mongo-scala-driver:4.3.3`
import $dep.`com.outr::scribe-slf4j:3.6.1`
import $dep.`com.lihaoyi::requests:0.6.9`

import java.time.LocalDate

import org.mongodb.scala.Document
import org.mongodb.scala.MongoClient
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.result.InsertOneResult

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

object Main extends App {

  implicit val ec: ExecutionContext = ExecutionContext.global

  val dbName = sys.env("WAKA_DB")
  val dbCollection = sys.env("WAKA_COLLECTION")
  val dbUser = sys.env("WAKA_USER")
  val dbPW = sys.env("WAKA_PW")
  val dbURI = sys.env("WAKA_URI")
  val apiKey = sys.env("WAKA_API_KEY")
  val date = LocalDate.now().toString()

  val uri: String = s"mongodb+srv://$dbUser:$dbPW@$dbURI"

  val client = MongoClient(uri)

  def store(
      collection: MongoCollection[Document],
      raw: String
  ): Future[Option[InsertOneResult]] =
    collection
      .insertOne(Document(raw))
      .headOption()

  def fetchAndStore(
      collection: MongoCollection[Document]
  ): Future[Option[InsertOneResult]] = {
    val r = requests.get(
      s"https://wakatime.com/api/v1/users/ckipp01/summaries/?start=${date}&end=${date}&api_key=${apiKey}"
    )
    if (r.is2xx) {
      scribe.info("Correctly fetched todays stats")
      store(collection, r.text())
    } else {
      scribe.error(
        s"Some went wrong getting your waka stats. Got back: ${r.statusCode} -- ${r.statusMessage}"
      )
      Future(None)
    }
  }

  val result = for {
    collection <- Future(client.getDatabase(dbName).getCollection(dbCollection))
    duplicated <- collection.find(equal("data.range.date", date)).headOption()
    stored <- duplicated match {
      case Some(_) =>
        scribe.warn(
          "Skipping, today's stats are already stored"
        )
        Future(None)
      case None =>
        scribe.info("No stats stored yet for today, fetching...")
        fetchAndStore(collection)
    }
  } yield stored

  result.onComplete {
    case Success(Some(value)) if value.wasAcknowledged() =>
      scribe.info("Inserted stats!")
      client.close()
    case Success(Some(_)) =>
      scribe.error(
        "I think somethingwent wrong, the insert wasn't acknowledged"
      )
      client.close()
    case Success(_) => client.close()
    case Failure(exception) =>
      scribe.error(exception.getMessage())
      client.close()
  }

}
