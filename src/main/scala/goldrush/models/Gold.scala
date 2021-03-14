package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Gold {
  type Gold = String

  implicit val codec: JsonValueCodec[Gold] = JsonCodecMaker.make
  implicit val listCodec: JsonValueCodec[Array[Gold]] = JsonCodecMaker.make
}
