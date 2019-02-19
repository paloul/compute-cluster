package ai.beyond.fpt.mvp.compute.serializers

import akka.serialization.SerializerWithStringManifest
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.{read, write}

class ComputeAgentSerializer extends SerializerWithStringManifest {
  def identifier:Int = 9991

  implicit val formats = Serialization.formats(NoTypeHints)

  def manifest(o:AnyRef):String = o.getClass.getName

  def fromBinary(bytes:Array[Byte], manifest:String):AnyRef = {

    val m = Manifest.classType[AnyRef](Class.forName(manifest))

    val json = new String(bytes, "utf8")

    read[AnyRef](json)(formats, m)
  }

  def toBinary(o:AnyRef):Array[Byte] = {

    val json = write(o)

    json.getBytes()
  }

}