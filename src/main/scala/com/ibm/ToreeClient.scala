package com.ibm


import com.typesafe.config.ConfigFactory
import org.apache.toree.kernel.protocol.v5.client.boot.ClientBootstrap
import com.typesafe.config.Config
import org.apache.toree.kernel.protocol.v5.MIMEType
import org.apache.toree.kernel.protocol.v5.client.SparkKernelClient
import org.apache.toree.kernel.protocol.v5.client.boot.layers.{StandardHandlerInitialization, StandardSystemInitialization}
import org.apache.toree.kernel.protocol.v5.client.execution.DeferredExecution
import org.apache.toree.kernel.protocol.v5.content.{ExecuteReplyError, ExecuteReplyOk, ExecuteResult, StreamContent}
import py4j.GatewayServer

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

class ToreeGateway(client: SparkKernelClient) {
  final val log = LoggerFactory.getLogger(this.getClass.getName.stripSuffix("$"))

  private def handleResult(promise:Promise[String], result: ExecuteResult) = {
    // promise.success(result.data(MIMEType.PlainText))
    promise.success(result.content)
    log.warn(s"Result was: ${result.data(MIMEType.PlainText)}")
  }

  private def handleSuccess(promise:Promise[String], executeReplyOk: ExecuteReplyOk) = {
    log.warn(s"Successful code completion")
    promise.complete(Try("done"))
  }

  private def handleError(promise:Promise[String], reply:ExecuteReplyError) {
    log.warn(s"Error was: ${reply.ename.get}")
    promise.failure(new Throwable("Error evaluating paragraph: " + reply.content))
  }

  private def handleStream(promise:Promise[String], content: StreamContent) {
    log.warn(s"Received streaming content ${content.name} was: ${content.text}")
    promise.success(content.text)
  }

  def eval(code: String): Object = {
    val promise = Promise[String]
    try {
      val exRes: DeferredExecution = client.execute(code)
      .onResult(executeResult => {
        handleResult(promise, executeResult)
      }).onError(executeReplyError =>{
        handleError(promise, executeReplyError)
      }).onSuccess(executeReplyOk => {
        handleSuccess(promise, executeReplyOk)
      }).onStream(streamResult => {
        handleStream(promise, streamResult)
      })

    } catch {
      case t : Throwable => log.info("Error proxying request: " + t.getMessage, t)
    }

    Await.result(promise.future, Duration.Inf)
  }
}

object ToreeClient extends App {

  final val log = LoggerFactory.getLogger(this.getClass.getName.stripSuffix("$"))

  def getConfigurationFilePath: String = {
    var filePath = "/opt/toree_proxy/conf/profile.json"

    if (args.length == 0) {
      for (arg <- args) {
        if (arg.contains("json")) {
          filePath = arg
        }
      }
    }

    filePath
  }

  log.info("Application Initialized from " + new java.io.File(".").getCanonicalPath)
  log.info("With the following parameters:" )
  if (args.length == 0 ) {
    log.info(">>> NONE" )
  } else {
    for (arg <- args) {
      log.info(">>> Arg :" + arg )
    }
  }

  // Parse our configuration and create a client connecting to our kernel
  val configFileContent = scala.io.Source.fromFile(getConfigurationFilePath).mkString
  log.info(">>> Configuration in use " + configFileContent)
  val config: Config = ConfigFactory.parseString(configFileContent)

  val client = (new ClientBootstrap(config)
    with StandardSystemInitialization
    with StandardHandlerInitialization).createClient()

  val toreeGateway = new ToreeGateway(client)

  val gatewayServer: GatewayServer = new GatewayServer(toreeGateway)
  gatewayServer.start()
}
