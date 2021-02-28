package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

final case class DigRequest(licenseID: Int, posX: Int, posY: Int, depth: Int)

object DigRequest {
  implicit val codec: JsonValueCodec[DigRequest] = JsonCodecMaker.make

}
