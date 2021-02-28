package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

final case class Gold(value: String) extends AnyVal

object Gold {
  implicit val codec: JsonValueCodec[Gold] = JsonCodecMaker.make
  implicit val listCodec: JsonValueCodec[List[Gold]] = JsonCodecMaker.make
}
