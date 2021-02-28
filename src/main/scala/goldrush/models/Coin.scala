package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final case class Coin(value: Int) extends AnyVal

object Coin {
  implicit val codec: JsonValueCodec[Coin] = JsonCodecMaker.make
  implicit val listCodec: JsonValueCodec[List[Coin]] = JsonCodecMaker.make
}