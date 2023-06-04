import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import cats.syntax.all.*
import cats.effect.*

final case class Echo(msg_id: Int, echo: String)
given JsonValueCodec[Echo] = JsonCodecMaker.make

final case class EchoOk(`type`: String, msg_id: Int, in_reply_to: Int, echo: String)
given JsonValueCodec[EchoOk] = JsonCodecMaker.make

object Main extends IOApp.Simple {
  def run: IO[Unit] = Node
    .stateless[IO, Echo, EchoOk] { case (src, Echo(msgId, echo)) => List((src, EchoOk("echo_ok", _, msgId, echo))) }
    .flatMap(_.run)
}
