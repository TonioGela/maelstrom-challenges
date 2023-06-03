//> using scala 3.3.0
//> using platform native
//> using lib com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros::2.23.1
//> using lib co.fs2::fs2-io::3.7.0

import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import cats.effect.kernel.*
import cats.syntax.all.*
import cats.effect.std.*
import cats.effect.*
import fs2.io.*
import fs2.*

final case class State(nodeId: Option[String], nextMessageId: Int)

final case class Message(id: Option[Int], src: String, dest: String, body: Body) {
  def reply(f: Body => Body): Message = Message(None, src = dest, dest = src, f(body))
}

sealed trait Body(msgId: Option[Int], inReplyTo: Option[Int])

final case class Init(msgId: Option[Int], nodeId: String, nodeIds: List[String]) extends Body(msgId, None)

final case class InitOk(msgId: Option[Int], inReplyTo: Option[Int]) extends Body(msgId, inReplyTo)

final case class Echo(msgId: Int, echo: String) extends Body(msgId.some, None)
final case class EchoOk(msgId: Option[Int], inReplyTo: Int, echo: String) extends Body(msgId, inReplyTo.some)

final case class Error(inReplyTo: Option[Int], code: Int, text: String) extends Body(None, inReplyTo)

given JsonValueCodec[Message] = JsonCodecMaker.make(
  CodecMakerConfig
    .withTransientEmpty(false)
    .withTransientNone(true)
    .withDiscriminatorFieldName(Some("type"))
    .withRequireDiscriminatorFirst(false)
    .withAdtLeafClassNameMapper(JsonCodecMaker.simpleClassName.andThen(JsonCodecMaker.enforce_snake_case))
    .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
)

def messagesStream[F[_]: Sync: Console]: Stream[F, Message] = stdinUtf8(1024)
  .through(text.lines)
  .evalMap(s => Console[F].errorln(s"Received $s") >> Sync[F].delay(readFromString[Message](s)))
  .handleErrorWith(t => Stream.eval(Console[F].errorln(s"Unable to parse message", t)) >> messagesStream[F])

def messageOutput[F[_]: Sync: Console]: Pipe[F, Message, Unit] =
  _.map(writeToString[Message](_))
    .evalTap(s => Console[F].errorln(s"Sending $s"))
    .map(_ + "\n")
    .through(stdoutLines())

def reply: Message => Message = _.reply {
  case Init(msgId, nodeId, nodeIds)   => InitOk(1.some, msgId)
  case InitOk(_, inReplyTo)           => InitOk(None, None)
  case Error(inReplyTo, code, text)   => InitOk(None, None)
  case Echo(msgId, echo)              => EchoOk(msgId.some, msgId, echo)
  case EchoOk(msgId, inReplyTo, echo) => InitOk(None, None)
}

object Main extends IOApp.Simple {
  def run: IO[Unit] =
    messagesStream[IO]
      .map(reply)
      .through(messageOutput[IO])
      .compile
      .drain
}

// Manca l 'auto increment del message id

// Deferred per il node_id
// Risponde con una lista di messaggi che puo' essere lunga 0

// Node[S,I:JsonValueCodec, O: JsonValueCodec](State.empty) {
//   def handle( (S,I) => F[(S,O)])
// }
