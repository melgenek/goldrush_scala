package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object Coin {
  type Coin = Int

  implicit val plainArrayCodec: JsonValueCodec[Array[Int]] = JsonCodecMaker.make
  implicit val seqCodec: JsonValueCodec[Seq[Coin]] = JsonCodecMaker.make
}
