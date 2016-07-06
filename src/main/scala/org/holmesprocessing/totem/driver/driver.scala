package org.holmesprocessing.totem.driver

import java.util.concurrent.{Executors, ExecutorService}

import akka.actor.{ActorRef, ActorSystem, Props}
import org.holmesprocessing.totem.actors._
import org.holmesprocessing.totem.services.objdump.{ObjdumpSuccess, ObjdumpWork}
import org.holmesprocessing.totem.services.peid.{PEiDSuccess, PEiDWork}
import org.holmesprocessing.totem.services.peinfo.{PEInfoSuccess, PEInfoWork}
import org.holmesprocessing.totem.services.virustotal.{VirustotalSuccess, VirustotalWork}
import org.holmesprocessing.totem.services.yara.{YaraSuccess, YaraWork}
import org.holmesprocessing.totem.services.zipmeta.{ZipMetaSuccess, ZipMetaWork}

import org.holmesprocessing.totem.types._
import org.holmesprocessing.totem.util.DownloadSettings

import org.holmesprocessing.totem.util.Instrumented

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Random

object driver extends App with Instrumented {
  // Define constants 
  val DefaultPathConfigFile = "./config/totem.conf"

  lazy val execServ: ExecutorService = Executors.newFixedThreadPool(4000)
  val conf: Config = if (args.length > 0) {
    println("Using manual config file: " + args(0))
    ConfigFactory.parseFile(new File(args(0)))
  } else {
    println("Using default config file: " + DefaultPathConfigFile)
    ConfigFactory.parseFile(new File(DefaultPathConfigFile))
  }
  val system = ActorSystem("totem")

  println("Configuring details for downloading objects")
  val downloadConfig = DownloadSettings(
    conf.getString("totem.download_settings.download_directory"),
    conf.getInt("totem.download_settings.request_timeout"),
    conf.getInt("totem.download_settings.connection_timeout")
  )

  println("Configuring details for Rabbit queues")
  val hostConfig = HostSettings(
    conf.getString("totem.rabbit_settings.host.server"),
    conf.getInt("totem.rabbit_settings.host.port"),
    conf.getString("totem.rabbit_settings.host.username"),
    conf.getString("totem.rabbit_settings.host.password"),
    conf.getString("totem.rabbit_settings.host.vhost")
  )
  val exchangeConfig = ExchangeSettings(
    conf.getString("totem.rabbit_settings.exchange.name"),
    conf.getString("totem.rabbit_settings.exchange.type"),
    conf.getBoolean("totem.rabbit_settings.exchange.durable")
  )
  val workqueueConfig = QueueSettings(
    conf.getString("totem.rabbit_settings.workqueue.name"),
    conf.getString("totem.rabbit_settings.workqueue.routing_key"),
    conf.getBoolean("totem.rabbit_settings.workqueue.durable"),
    conf.getBoolean("totem.rabbit_settings.workqueue.exclusive"),
    conf.getBoolean("totem.rabbit_settings.workqueue.autodelete")
  )
  val resultQueueConfig = QueueSettings(
    conf.getString("totem.rabbit_settings.resultsqueue.name"),
    conf.getString("totem.rabbit_settings.resultsqueue.routing_key"),
    conf.getBoolean("totem.rabbit_settings.resultsqueue.durable"),
    conf.getBoolean("totem.rabbit_settings.resultsqueue.exclusive"),
    conf.getBoolean("totem.rabbit_settings.resultsqueue.autodelete")
  )

  val misbehaveQueueConfig = QueueSettings(
    conf.getString("totem.rabbit_settings.misbehavequeue.name"),
    conf.getString("totem.rabbit_settings.misbehavequeue.routing_key"),
    conf.getBoolean("totem.rabbit_settings.misbehavequeue.durable"),
    conf.getBoolean("totem.rabbit_settings.misbehavequeue.exclusive"),
    conf.getBoolean("totem.rabbit_settings.misbehavequeue.autodelete")
  )

  println("Configuring setting for Services")
  class TotemicEncoding(conf: Config) extends ConfigTotemEncoding(conf) { //this is a class, but we can probably make it an object. No big deal, but it helps on mem. pressure.
    def GeneratePartial(work: String): String = {
      work match {
        case "OBJDUMP" => Random.shuffle(services.getOrElse("objdump", List())).head
        case "PEID" => Random.shuffle(services.getOrElse("peid", List())).head
        case "PEINFO" => Random.shuffle(services.getOrElse("peinfo", List())).head
        case "VIRUSTOTAL" => Random.shuffle(services.getOrElse("virustotal", List())).head
        case "YARA" => Random.shuffle(services.getOrElse("yara", List())).head
        case "ZIPMETA" => Random.shuffle(services.getOrElse("zipmeta", List())).head
      }
    }

    def enumerateWork(key: Long, filename: String, workToDo: Map[String, List[String]]): List[TaskedWork] = {
      val w = workToDo.map({
        case ("OBJDUMP", li: List[String]) =>
          ObjdumpWork(key, filename, 60, "OBJDUMP", GeneratePartial("OBJDUMP"), li)
        case ("PEID", li: List[String]) =>
          PEiDWork(key, filename, 60, "PEID", GeneratePartial("PEID"), li)
        case ("PEINFO", li: List[String]) =>
          PEInfoWork(key, filename, 60, "PEINFO", GeneratePartial("PEINFO"), li)
        case ("VIRUSTOTAL", li: List[String]) =>
          VirustotalWork(key, filename, 60, "VIRUSTOTAL", GeneratePartial("VIRUSTOTAL"), li)
        case ("YARA", li: List[String]) =>
          YaraWork(key, filename, 60, "YARA", GeneratePartial("YARA"), li)
        case ("ZIPMETA", li: List[String]) =>
          ZipMetaWork(key, filename, 60, "ZIPMETA", GeneratePartial("ZIPMETA"), li)
        case (s: String, li: List[String]) =>
          UnsupportedWork(key, filename, 1, s, GeneratePartial(s), li)
        case _ => Unit
      }).collect({
        case x: TaskedWork => x
      })
      w.toList
    }

    def workRoutingKey(work: WorkResult): String = {
      work match {
        case x: ObjdumpSuccess => conf.getString("totem.services.objdump.resultRoutingKey")
        case x: PEiDSuccess => conf.getString("totem.services.peid.resultRoutingKey")
        case x: PEInfoSuccess => conf.getString("totem.services.peinfo.resultRoutingKey")
        case x: VirustotalSuccess => conf.getString("totem.services.virustotal.resultRoutingKey")
        case x: YaraSuccess => conf.getString("totem.services.yara.resultRoutingKey")
        case x: ZipMetaSuccess => conf.getString("totem.services.zipmeta.resultRoutingKey")
        case _ => ""
      }
    }
  }
  println("Completing configuration")
  val encoding = new TotemicEncoding(conf)

  println("Creating Totem Actors")
  val myGetter: ActorRef = system.actorOf(RabbitConsumerActor.props[ZooWork](hostConfig, exchangeConfig, workqueueConfig, encoding, Parsers.parseJ, downloadConfig).withDispatcher("akka.actor.my-pinned-dispatcher"), "consumer")
  val mySender: ActorRef = system.actorOf(Props(classOf[RabbitProducerActor], hostConfig, exchangeConfig, resultQueueConfig, misbehaveQueueConfig, encoding, conf.getString("totem.requeueKey")), "producer")

  println("Totem version " + conf.getString("totem.version") + " is running and ready to receive tasks")

  //////
  // Demo & Debug Zone
  // The following commented section is left to provide manual input that is useful when
  // debugging a totem setup. Totem otherwise will only pull from the Rabbit queue.
  //////
  //
  //  val zoowork = ZooWork("http://localhost/rar.exe", "http://localhost/rar.exe", "winrar.exe", Map[String, List[String]]("YARA" -> List[String]()), 0)
  //
  //  val json = (
  //    ("primaryURI" -> zoowork.primaryURI) ~
  //      ("secondaryURI" -> zoowork.secondaryURI) ~
  //      ("filename" -> zoowork.filename) ~
  //      ("tasks" -> zoowork.tasks) ~
  //      ("attempts" -> zoowork.attempts)
  //    )
  //
  //  private[this] val loading = metrics.timer("loading")
  //
  //  val j = loading.time({
  //    compact(render(json))
  //  })
  //
  //  mySender ! Send(RMQSendMessage(j.getBytes, workqueueConfig.routingKey))
  //////
}