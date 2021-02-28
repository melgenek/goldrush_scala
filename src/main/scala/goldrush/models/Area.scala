package goldrush.models

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._

final case class Area(posX: Int, posY: Int, sizeX: Int, sizeY: Int)

object Area {
  implicit val codec: JsonValueCodec[Area] = JsonCodecMaker.make
}

final case class ExploreReport(area: Area, amount: Int)

object ExploreReport {
  implicit val codec: JsonValueCodec[ExploreReport] = JsonCodecMaker.make
}