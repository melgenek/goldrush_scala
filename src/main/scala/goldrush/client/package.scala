package goldrush

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import sttp.client3._
import sttp.model.MediaType
import zio.Has

package object client {

  type MineClient = Has[MineClient.Service]

  def asJsoniterAlways[A: JsonValueCodec]: ResponseAs[A, Any] = asJsoniter.getRight

  def asJsoniter[A: JsonValueCodec]: ResponseAs[Either[String, A], Any] = asByteArray.mapRight(readFromArray(_))

  implicit def jsoniterBodySerializer[A: JsonValueCodec]: BodySerializer[A] =
    b => ByteArrayBody(writeToArray(b), MediaType.ApplicationJson)

}
