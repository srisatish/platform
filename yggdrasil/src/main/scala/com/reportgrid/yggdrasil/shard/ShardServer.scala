/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.reportgrid.yggdrasil
package shard 

import com.reportgrid.analytics.Path
import com.reportgrid.common._
import com.reportgrid.util._
import com.reportgrid.util.Bijection._
import kafka._
import leveldb._

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.dispatch.Await
import akka.dispatch.Future
import akka.dispatch.Promise
import akka.util.Timeout
import akka.util.duration._
import akka.util.Duration
import akka.actor.Terminated
import akka.actor.ReceiveTimeout
import akka.actor.ActorTimeoutException

import blueeyes.util._
import blueeyes.json.Printer
import blueeyes.json.JsonAST._
import blueeyes.json.JsonParser
import blueeyes.json.xschema._
import blueeyes.json.xschema.Extractor._
import blueeyes.json.xschema.DefaultSerialization._
import blueeyes.persistence.cache.Cache
import blueeyes.persistence.cache.CacheSettings
import blueeyes.persistence.cache.ExpirationPolicy

import com.weiglewilczek.slf4s._
import _root_.kafka.consumer._

import java.io.File
import java.io.FileReader
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.util.Properties
import java.util.concurrent.TimeUnit

import org.scalacheck.Gen._

import com.reportgrid.common._
import com.reportgrid.common.Event
import com.reportgrid.common.util.RealisticIngestMessage

import scala.collection._
import scala.annotation.tailrec

import scalaz._
import scalaz.std._
import scalaz.std.AllInstances._
import scalaz.syntax._
import scalaz.syntax.validation._
import scalaz.syntax.traverse._
import scalaz.syntax.semigroup._
import scalaz.effect._
import scalaz.iteratee.EnumeratorT

// On startup
// - load config/metadata from filesystem 
// -- collect Map[ProjectionDescriptor, File]
// -- walk Map[ProjectionDescriptor, File] 
// - repair/rebuild if necessary (leave as a todo)
// - create/start various actors
// - wait for shutdown hook
// - shutdown
// -- request all actors shutdown
// -- wait for all actors to shutdown
// -- exit

class ShardServer {
  def run(config: ShardServerConfig): Unit = {
    
    val system = ActorSystem("Shard Actor System")
    
    val routingTable = new SingleColumnProjectionRoutingTable
    val metadataActor: ActorRef = system.actorOf(Props(new ShardMetadataActor(config.metadata, ShardMetadata.dummyCheckpoints)))

    val router = system.actorOf(Props(new RoutingActor(config.baseDir, config.descriptors, routingTable, metadataActor)))
    
    val consumer = new KafkaConsumer(config.properties, router)

    val consumerThread = new Thread(consumer)
    consumerThread.start

    println("Shard Server started...")
   
  }
}

object ShardLoader extends RealisticIngestMessage {

  def gracefulStop(target: ActorRef, timeout: Duration)(implicit system: ActorSystem): Future[Boolean] = {
    if (target.isTerminated) {
      Promise.successful(true)
    } else {
      val result = Promise[Boolean]()
      system.actorOf(Props(new Actor {
        // Terminated will be received when target has been stopped
        context watch target
        target ! PoisonPill
        // ReceiveTimeout will be received if nothing else is received within the timeout
        context setReceiveTimeout timeout

        def receive = {
          case Terminated(a) if a == target ⇒
            result success true
            context stop self
          case ReceiveTimeout ⇒
            result failure new ActorTimeoutException(
              "Failed to stop [%s] within [%s]".format(target.path, context.receiveTimeout))
            context stop self
        }
      }))
      result
    }
  }

  def main(args: Array[String]) {
    if(args.length < 2) sys.error("Must provide path and number of events to generate.")
    val base = new File(args(0))
    val count = args(1).toInt
    
    val events = containerOfN[List, Event](1, genEvent).sample.get

    load(base, events, count)

    
  }

  def load(baseDir: File, events: List[Event], count: Int) {
    baseDir.mkdirs

    println("Paths: " + events(0).content.size)

    val system = ActorSystem("shard_loader")

    implicit val dispatcher = system.dispatcher

    val routingTable = new SingleColumnProjectionRoutingTable
    val metadataActor: ActorRef = system.actorOf(Props(new ShardMetadataActor(mutable.Map(), ShardMetadata.dummyCheckpoints)))

    val router = system.actorOf(Props(new RoutingActor(baseDir, mutable.Map(), routingTable, metadataActor)))
    
    implicit val timeout: Timeout = 30 seconds 
   
    val finalEvents = 0.until(count).map(_ => events).flatten

    finalEvents.zipWithIndex.foreach {
      case (ev, idx) => router ! EventMessage(0, idx, ev) 
    }

    println("Initiating shutdown")

    Await.result(Future.sequence(gracefulStop(router, 300 seconds)(system) :: gracefulStop(metadataActor, 300 seconds)(system) :: Nil), 300 seconds)
    
    println("Actors stopped")
    
    println("Insert total: " + finalEvents.size)

    system.shutdown
  }
}

class KafkaConsumer(props: Properties, router: ActorRef) extends Runnable {
  private lazy val consumer = initConsumer

  def initConsumer = {
    val config = new ConsumerConfig(props)
    Consumer.create(config)
  }

  def run {
    val rawEventsTopic = props.getProperty("querio.storage.topic.raw", "raw")

    val streams = consumer.createMessageStreams(Map(rawEventsTopic -> 1))


    for(rawStreams <- streams.get(rawEventsTopic); stream <- rawStreams; message <- stream) {
      router ! IngestMessageSerialization.readMessage(message.buffer) 
    }
  }

  def requestStop {
    consumer.shutdown
  }
}

class ShardServerConfig(val properties: Properties, val baseDir: File, val descriptors: mutable.Map[ProjectionDescriptor, File], val metadata: mutable.Map[ProjectionDescriptor, Seq[mutable.Map[MetadataType, Metadata]]])

object ShardServerConfig extends Logging {
  def fromFile(propsFile: File): IO[Validation[Error, ShardServerConfig]] = IOUtils.readPropertiesFile { propsFile } flatMap { fromProperties } 
  
  def fromProperties(props: Properties): IO[Validation[Error, ShardServerConfig]] = {
    val baseDir = extractBaseDir { props }
    loadDescriptors { baseDir } flatMap { desc => loadMetadata(desc) map { _.map { new ShardServerConfig(props, baseDir, desc, _) } } }
  }

  def extractBaseDir(props: Properties): File = new File(props.getProperty("querio.storage.root", "."))

  def loadDescriptors(baseDir: File): IO[mutable.Map[ProjectionDescriptor, File]] = {

    def loadMap(baseDir: File) = {
      IOUtils.walkSubdirs(baseDir).map{ _.foldLeft( mutable.Map[ProjectionDescriptor, File]() ){ (map, dir) =>
        println("loading: " + dir)
        LevelDBProjection.descriptorSync(dir).read match {
          case Some(dio) => dio.unsafePerformIO.fold({ t => logger.warn("Failed to restore %s: %s".format(dir, t)); map },
                                                     { pd => map + (pd -> dir) })
          case None      => map
        }
      }}
    }

    loadMap(baseDir)
  }

  type MetadataSeq = Seq[mutable.Map[MetadataType, Metadata]]
  
  def loadMetadata(descriptors: mutable.Map[ProjectionDescriptor, File]): IO[Validation[Error, mutable.Map[ProjectionDescriptor, MetadataSeq]]] = {

    type MetadataTuple = (ProjectionDescriptor, MetadataSeq)

    def readAll(descriptors: mutable.Map[ProjectionDescriptor, File]): IO[Validation[Error, Seq[MetadataTuple]]] = {
      val validatedEntries = descriptors.toList.map{ case (d, f) => readSingle(f) map { _.map((d, _)) } }.sequence[IO, Validation[Error, (ProjectionDescriptor, MetadataSeq)]]

      validatedEntries.map(flattenValidations)
    }

    def readSingle(dir: File): IO[Validation[Error, MetadataSeq]] = {
      import JsonParser._
      val metadataFile = new File(dir, "projection_metadata.json")
      IOUtils.readFileToString(metadataFile).map { content => 
        val validatedTuples = parse(content.getOrElse("")).validated[List[List[(MetadataType, Metadata)]]]
        validatedTuples.map( _.map( mutable.Map(_: _*)))
      }
    }

    readAll(descriptors).map { _.map { mutable.Map(_: _*) } }
  }

  def flattenValidations[A](l: Seq[Validation[Error,A]]): Validation[Error, Seq[A]] = {
    l.foldLeft[Validation[Error, List[A]]]( Success(List()) ) { (acc, el) => (acc, el) match {
      case (Success(ms), Success(m)) => Success(ms :+ m)
      case (Failure(e1), Failure(e2)) => Failure(e1 |+| e2)
      case (_          , Failure(e)) => Failure(e)
    }}
  }
}

object IOUtils {

  val dotDirs = "." :: ".." :: Nil
  
  def isNormalDirectory(f: File) = f.isDirectory && !dotDirs.contains(f.getName) 

  def walkSubdirs(root: File): IO[Seq[File]] =
    IO { if(!root.isDirectory) List.empty else root.listFiles.filter( isNormalDirectory ) }

  def readFileToString(f: File): IO[Option[String]] = {
    def readFile(f: File): String = {
      val in = scala.io.Source.fromFile(f)
      val content = in.mkString
      in.close
      content
    }
    IO { if(f.exists && f.canRead) Some(readFile(f)) else None }
  }

  def readPropertiesFile(s: String): IO[Properties] = readPropertiesFile { new File(s) } 
  
  def readPropertiesFile(f: File): IO[Properties] = IO {
    val props = new Properties
    props.load(new FileReader(f))
    props
  }

  def writeToFile(s: String, f: File): IO[Unit] = IO {
    val writer = new PrintWriter(new PrintWriter(f))
    writer.println(s)
    writer.close
  }

}

object ShardServer {
  def main(args: Array[String]) = loadConfig { args(0) } map { runServerOrDie } unsafePerformIO

  def runServerOrDie(validation: Validation[Error, ShardServerConfig]) {
    validation match {
      case Success(config) => new ShardServer run config
      case Failure(e)      => println("Error loading server config: " + e)
    }
  }

  def loadConfig(filename: String) = ShardServerConfig.fromFile { new File(filename) }
}

object ShardDemoUtil {

  def main(args: Array[String]) {
    val default = if(args.length > 0) args(0) else "/tmp/test/"
    val props = new Properties
    props.setProperty("querio.storage.root", default)
    bootstrapTest(props).unsafePerformIO.map( t => println(t.descriptors + "|" + t.metadata) )
  }

  def bootstrapTest(properties: Properties): IO[Validation[Error, ShardServerConfig]] =
    ShardServerConfig.fromProperties(properties)

  def writeDummyShardMetadata() {
    val md = ShardMetadata.dummyProjections
   
    val rawEntries = 0.until(md.size) zip md.toSeq

    val descriptors = rawEntries.foldLeft( Map[ProjectionDescriptor, File]() ) {
      case (acc, (idx, (pd, md))) => acc + (pd -> new File("/tmp/test/desc"+idx))
    }

    val metadata = rawEntries.foldLeft( Map[ProjectionDescriptor, Seq[Map[MetadataType, Metadata]]]() ) {
      case (acc, (idx, (pd, md))) => acc + (pd -> md)
    }

    writeAll(descriptors, metadata).unsafePerformIO
  }

  def writeAll(descriptors: Map[ProjectionDescriptor, File], metadata: Map[ProjectionDescriptor,Seq[Map[MetadataType, Metadata]]]): IO[Unit] = 
    writeDescriptors(descriptors) unsafeZip writeMetadata(descriptors, metadata) map { _ => () }

  def writeDescriptors(descriptors: Map[ProjectionDescriptor, File]): IO[Unit] = 
    descriptors.map {
      case (pd, f) => {
        if(!f.exists) f.mkdirs
        IOUtils.writeToFile(Printer.pretty(Printer.render(pd.serialize)), new File(f, "projection_descriptor.json"))
      }
    }.toList.sequence[IO,Unit].map { _ => () }

  def writeMetadata(descriptors: Map[ProjectionDescriptor, File], metadata: Map[ProjectionDescriptor,Seq[Map[MetadataType, Metadata]]]): IO[Unit] =
    metadata.map {
      case (pd, md) => {
        descriptors.get(pd).map { f =>
          if(!f.exists) f.mkdirs
          IOUtils.writeToFile(Printer.pretty(Printer.render(md.map( _.toSeq ).serialize)), new File(f, "projection_metadata.json"))
        }.getOrElse(IO { () })
      }
    }.toList.sequence[IO,Unit].map { _ => () }
}

// vim: set ts=4 sw=4 et: