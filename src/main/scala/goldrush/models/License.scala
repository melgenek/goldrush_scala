package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import zio.UIO

final case class License(id: Int, digAllowed: Int, digUsed: Int) {
  def isUsed: Boolean = digAllowed == digUsed
}

object License {
  final val EmptyLicense = License(0, 0, 0)

  implicit val codec: JsonValueCodec[License] = JsonCodecMaker.make
  implicit val listCodec: JsonValueCodec[List[License]] = JsonCodecMaker.make
}

final case class LicenseLease(licenseId: Int, requestMore: UIO[Unit])
