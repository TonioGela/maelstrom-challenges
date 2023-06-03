import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import cats.syntax.all.*
import cats.effect.*

final case class Echo(msg_id: Int, echo: String)
given JsonValueCodec[Echo] = JsonCodecMaker.make

final case class EchoOk(`type`: String, msg_id: Option[Int], in_reply_to: Int, echo: String)
given JsonValueCodec[EchoOk] = JsonCodecMaker.make

opaque type State = Unit
object State {
  val empty: State = ()
}

object Main extends IOApp.Simple {
  def run: IO[Unit] = Node
    .create[IO, State, Echo, EchoOk](State.empty) { case (State.empty, src, Echo(msgId, echo)) =>
      (State.empty, List((src, (id: Int) => EchoOk("echo_ok", id.some, msgId, echo))))
    }
    .flatMap(_.run)
}
