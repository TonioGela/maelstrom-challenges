import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import cats.effect.kernel.*
import cats.syntax.all.*
import cats.effect.std.*
import cats.effect.*
import fs2.io.*
import fs2.*
import Node.*

class Node[F[_]: Sync: Console, S, I: JsonValueCodec, O: JsonValueCodec] private (
    private val nodeId: Deferred[F, String],
    private val counter: Ref[F, Int],
    private val state: AtomicCell[F, S],
    private val handle: (S, String, I) => (S, List[(String, Int => O)])
) {

  private def nextId: F[Int] = counter.getAndUpdate(_ + 1)
  private def log: String => F[Unit] = Console[F].errorln
  private def forge[T](f: Int => Message[T]): F[Message[T]] = nextId.map(f)

  private def initializationProcedure: F[Unit] = for {
    line <- Console[F].readLine
    _ <- Console[F].errorln(s"Received $line")
    message <- Sync[F].delay(readFromString[Message[Init]](line))
    msgId <- nextId
    initOk = message.reply[InitOk](init => InitOk("init_ok", msgId.some, init.msgId))
    _ <- nodeId.complete(message.body.nodeId)
    _ <- log(s"Initialized node as ${message.body.nodeId}")
    response <- Sync[F].delay(writeToString[Message[InitOk]](initOk))
    _ <- Console[F].errorln(s"Sending $response")
    _ <- Console[F].println(response)
  } yield ()

  private def businessLogic: Stream[F, Unit] = messagesStream[F, I]
    .evalMap(message =>
      for {
        payloads <- state.modify(s => handle(s, message.src, message.body))
        nodeId <- nodeId.get
        messages <- payloads.traverse { (dest, body) => forge[O](i => Message(None, nodeId, dest, body(i))) }
      } yield messages
    )
    .through(messageOutput)

  def run: F[Unit] = initializationProcedure >> businessLogic.compile.drain
}

object Node {
  def create[F[_]: Async: Console, S, I: JsonValueCodec, O: JsonValueCodec](empty: S)(
      handle: (S, String, I) => (S, List[(String, Int => O)])
  ): F[Node[F, S, I, O]] = for {
    nodeId <- Deferred[F, String]
    counter <- Ref.of[F, Int](0)
    state <- AtomicCell[F].of[S](empty)
  } yield new Node[F, S, I, O](nodeId, counter, state, handle)

  def stateless[F[_]: Async: Console, I: JsonValueCodec, O: JsonValueCodec](
      handle: (String, I) => List[(String, Int => O)]
  ): F[Node[F, Unit, I, O]] = for {
    nodeId <- Deferred[F, String]
    counter <- Ref.of[F, Int](0)
    state <- AtomicCell[F].of[Unit](())
    statelessHandle = (_: Unit, s: String, i: I) => ((), handle(s, i))
  } yield new Node[F, Unit, I, O](nodeId, counter, state, statelessHandle)

  private final case class Message[T](id: Option[Int], src: String, dest: String, body: T) {
    def reply[V](f: T => V): Message[V] = Message[V](None, src = dest, dest = src, f(body))
  }

  private implicit def foo[T: JsonValueCodec]: JsonValueCodec[Message[T]] = JsonCodecMaker.make[Message[T]](
    CodecMakerConfig
      .withTransientEmpty(false)
      .withTransientNone(true)
      .withDiscriminatorFieldName(Some("type"))
      .withRequireDiscriminatorFirst(false)
      .withAdtLeafClassNameMapper(JsonCodecMaker.simpleClassName.andThen(JsonCodecMaker.enforce_snake_case))
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )

  private final case class Init(msgId: Option[Int], nodeId: String, nodeIds: List[String])
  private given JsonValueCodec[Init] = JsonCodecMaker.make(
    CodecMakerConfig
      .withTransientEmpty(false)
      .withTransientNone(true)
      .withDiscriminatorFieldName(Some("type"))
      .withRequireDiscriminatorFirst(false)
      .withAdtLeafClassNameMapper(JsonCodecMaker.simpleClassName.andThen(JsonCodecMaker.enforce_snake_case))
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )

  private final case class InitOk(`type`: String, msg_id: Option[Int], in_reply_to: Option[Int])
  private given JsonValueCodec[InitOk] = JsonCodecMaker.make

  private def messagesStream[F[_]: Sync: Console, T: JsonValueCodec]: Stream[F, Message[T]] = stdinUtf8(1024)
    .through(text.lines)
    .evalTap(s => Console[F].errorln(s"Received $s"))
    .evalMap(s => Sync[F].delay(readFromString[Message[T]](s)))

  private def messageOutput[F[_]: Sync: Console, T: JsonValueCodec]: Pipe[F, List[Message[T]], Unit] =
    _.map(ms => ms.map(writeToString[Message[T]](_)).mkString("", "\n", "\n"))
      .evalTap(s => Console[F].error(s"Sending $s"))
      .through(stdoutLines())
}
