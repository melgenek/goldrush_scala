package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import zio.UIO

final case class License(id: Int, digAllowed: Int, digUsed: Int) {
  def isUsed: Boolean = digAllowed == digUsed
}

object License {
  implicit val codec: JsonValueCodec[License] = JsonCodecMaker.make
}

final case class LicenseLease(licenseId: Int, requestMore: UIO[Unit])
