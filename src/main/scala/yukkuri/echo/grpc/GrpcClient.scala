package yukkuri.echo.grpc

import java.util.concurrent.TimeUnit

import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannel, ManagedChannelBuilder, StatusRuntimeException}
import yukkuri.echo.grpc.messages.{EchoRequest, EchoResponse}
import yukkuri.echo.grpc.service.EchoServiceGrpc
import yukkuri.echo.grpc.service.EchoServiceGrpc.{EchoServiceBlockingStub, EchoServiceStub}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise

object GrpcClient extends App {

  val channel =
    ManagedChannelBuilder.forAddress("localhost", 50051).usePlaintext.build
  val blockingStub    = EchoServiceGrpc.blockingStub(channel)
  val nonBlockingStub = EchoServiceGrpc.stub(channel)
  val client          = new GrpcClient(channel, blockingStub, nonBlockingStub)

  try {
    client.unary()
    client.clientStreaming()
    client.serverStreaming()
    client.bidirectionalStreaming()
  } finally {
    client.shutdown()
  }
}

class GrpcClient private (
    private val channel: ManagedChannel,
    private val blockingStub: EchoServiceBlockingStub,
    private val nonBlockingStub: EchoServiceStub
) {

  def shutdown() = {
    channel.shutdown.awaitTermination(5, TimeUnit.SECONDS)
  }

  def unary() = {
    val request =
      EchoRequest("unary request", ByteString.copyFromUtf8("binary message"))
    try {
      val response = blockingStub.unary(request)
      println(s"unary response message: ${response.message}, binaryMessage: ${response.binaryMessage.toStringUtf8}")
    } catch {
      case e: StatusRuntimeException => println("RPC status: " + e.getStatus)
    }
  }

  def clientStreaming() = {

    val resObs = new StreamObserver[EchoResponse] {
      override def onError(t: Throwable)       = println("clientStreaming failed")
      override def onCompleted()               = println("clientStreaming complete")
      override def onNext(value: EchoResponse) = println(s"clientStreaming response: ${value.message}")
    }

    val reqObs: StreamObserver[EchoRequest] = nonBlockingStub.clientStreaming(resObs)

    reqObs.onNext(EchoRequest("clientStreaming request 1"))
    Thread.sleep(2000)
    reqObs.onNext(EchoRequest("clientStreaming request 2"))
    Thread.sleep(2000)
    reqObs.onNext(EchoRequest("clientStreaming request 3"))

    reqObs.onCompleted()

  }

  def serverStreaming() = {

    val promise = Promise[String]()

    val resObs = new StreamObserver[EchoResponse] {
      override def onError(t: Throwable) = println("serverStreaming failed")
      override def onCompleted() = {
        promise.success("serverStreaming complete")
      }
      override def onNext(value: EchoResponse) = println(s"serverStreaming response: ${value.message}")
    }

    nonBlockingStub.serverStreaming(EchoRequest(message = "serverStreaming request", repeat = 3), resObs)

    val res = promise.future
    res.onComplete(println)

  }

  def bidirectionalStreaming() = {

    val promise = Promise[String]()

    val resObs = new StreamObserver[EchoResponse] {
      override def onError(t: Throwable) = println("bidirectionalStreaming failed")
      override def onCompleted() = {
        promise.success("bidirectionalStreaming complete")
      }
      override def onNext(value: EchoResponse) = println(s"bidirectionalStreaming response: ${value.message}")
    }

    val reqObs: StreamObserver[EchoRequest] = nonBlockingStub.bidirectionalStreaming(resObs)

    reqObs.onNext(EchoRequest("bidirectionalStreaming unary request"))
    reqObs.onNext(EchoRequest(message = "bidirectionalStreaming request delay 3 repeat 10", delaySec = 3, repeat = 5))
    reqObs.onNext(EchoRequest(message = "bidirectionalStreaming request delay 2 repeat 10", delaySec = 2, repeat = 5))

    Thread.sleep(20 * 1000)

    // server complete
    reqObs.onNext(EchoRequest(message = "bidirectionalStreaming server complete", delimit = true))
    reqObs.onNext(EchoRequest("this message is ignore"))

    // or client complete
    // reqObs.onCompleted()

    val res = promise.future
    res.onComplete(println)

  }

}
