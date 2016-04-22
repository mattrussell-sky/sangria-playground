package models

import com.google.inject._
import sangria.schema._
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.ExecutionContext

import akka.actor._
import akka.event.Logging
import akka.pattern.{ CircuitBreaker, ask }
import akka.util.Timeout

import scala.collection.mutable.HashMap
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.ws._
import play.api.http.HttpEntity

case class Customer(id: Int, name: String, emailAddress: String, titleOpt: Option[String] = None)

case class Product(id: Int, name: String, productType: String)

case class GetPortfolio(customerId: Int)

@Singleton
class Repository @Inject() (implicit executionContext: ExecutionContext, implicit val system: ActorSystem, ws: WSClient) {
  println("Creating repository " + this)
  val breaker =
    new CircuitBreaker(system.scheduler,
      maxFailures = 1,
      callTimeout = 10 seconds,
      resetTimeout = 10 seconds).
      onOpen(println("circuit breaker opened!")).
      onClose(println("circuit breaker closed!")).
      onHalfOpen(println("circuit breaker half-open"))

  val getPortfolioActor = system.actorOf(Props[GetPortfolioActor])

  var customers = Seq(
    Customer(1, "Alice", "alice@hotmail.com"),
    Customer(2, "Bob", "bob@hotmail.com"),
    Customer(3, "Charlie", "charlie@hotmail.com"))

  def getCustomer(id: Int, fetchTitle: Boolean): Future[Option[Customer]] = {
    val customerOpt = customers.find(_.id == id)
    if (!fetchTitle)
      return Future.successful(customerOpt)
    val res: Option[Future[Customer]] = customerOpt.map(customer ⇒ enrichCustomers(Seq(customer)).map(_.head))
    def transform[A](o: Option[Future[A]]): Future[Option[A]] =
      o.map(f ⇒ f.map(Option(_))).getOrElse(Future.successful(None))
    transform(res)
  }

  private def enrichCustomers(customers: Seq[Customer]): Future[Seq[Customer]] = {
    val request: WSRequest = ws.url("http://e05-identity.mcs.bskyb.com/mcs-identity/contact/pid-13669914987431642642322")
    println("HTTP request sent")
    request.execute().map { response ⇒
      val title = (response.json \ "title").as[String]
      customers.map(customer ⇒ customer.copy(titleOpt = Some(title)))
    }
  }

  def getCustomers: Future[Seq[Customer]] = enrichCustomers(customers)

  def getPortfolio(customerId: Int): Future[Seq[Product]] = {
    implicit val timeout = Timeout(10 seconds)
    val message = GetPortfolio(customerId)
    val response = breaker.withCircuitBreaker(getPortfolioActor ? message)
    response.map(_.asInstanceOf[Seq[Product]]).recover {
      case t ⇒
        println("Error in get portfolio")
        t.printStackTrace()
        Seq()
    }
  }

  def addCustomer(name: String, email: String): Customer = {
    println(s"addCustomer($name, $email)")
    val nextId = customers.map(_.id).max + 1
    val customer = Customer(nextId, name, email)
    customers = customers :+ customer
    println(s"Added customer with id " + customer.id)
    println(customers)
    customer
  }

}

class GetPortfolioActor extends Actor {
  val log = Logging(context.system, this)

  val products = Seq(
    Product(1, "Sports", "Subscription"),
    Product(2, "Movies", "Subscription"),
    Product(3, "HD Box", "Hardware"))

  override def receive = {
    case GetPortfolio(customerId) ⇒
      // Thread.sleep(5000)
      sender() ! products
    case o ⇒
      Status.Failure(new IllegalArgumentException(o.toString))
  }

}

object SpikeSchema {


  lazy val CustomerType = ObjectType("Customer", () ⇒ fields[Repository, Customer](
    Field("id", IntType, resolve = _.value.id),
    Field("title", StringType, resolve = _.value.titleOpt.get),
    Field("name", StringType, resolve = _.value.name),
    Field("emailAddress", StringType, resolve = _.value.emailAddress),
    Field("portfolio", ListType(ProductType), resolve = ctx ⇒ ctx.ctx.getPortfolio(ctx.value.id))))

  lazy val ProductType = ObjectType("Product", () ⇒ fields[Repository, Product](
    Field("id", IntType, resolve = _.value.id),
    Field("name", StringType, resolve = _.value.name),
    Field("productType", StringType, resolve = _.value.productType)))

  object Arguments {
    val CustomerId = Argument("id", IntType, description = "A customer ID")
    val Name = Argument("name", StringType)
    val Email = Argument("email", StringType)

  }

  val Queries = ObjectType("Query", fields[Repository, Unit](
    Field("customer", OptionType(CustomerType),
      arguments = List(Arguments.CustomerId),
      resolve = Projector((ctx, f) ⇒ {
        val fetchTitle = f.exists(_.name == "title")
        ctx.ctx.getCustomer(ctx arg Arguments.CustomerId, fetchTitle)
      })),
    Field("customers", ListType(CustomerType),
      resolve = ctx ⇒ ctx.ctx.getCustomers)))

  private def resolveAddCustomer(ctx: Context[Repository, Unit]): Customer = {
    val name = ctx.arg(Arguments.Name)
    val email = ctx.arg(Arguments.Email)
    ctx.ctx.addCustomer(name, email)
  }

  val MutationType = ObjectType("Mutation", fields[Repository, Unit](
    Field("addCustomer", CustomerType,
      arguments = List(Arguments.Name, Arguments.Email),
      resolve = ctx ⇒ resolveAddCustomer(ctx))))

  val SpikeSchema = Schema(Queries, Some(MutationType))

}

