/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play.api.libs.json

import java.io.InputStream
import scala.annotation.{switch, tailrec}
import scala.collection._
import scala.collection.mutable.ListBuffer

import com.fasterxml.jackson.core.{JsonTokenId, JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.`type`.TypeFactory
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.ser.Serializers

import play.api.data.validation.ValidationError

case class JsResultException(errors: Seq[(JsPath, Seq[ValidationError])])
    extends RuntimeException("JsResultException(errors:%s)".format(errors))

/**
  * Generic json value
  */
sealed trait JsValue extends JsReadable {
  override def toString = Json.stringify(this)

  def validate[A](implicit rds: Reads[A]) = rds.reads(this)
}

object JsValue {
  import scala.language.implicitConversions
  implicit def jsValueToJsLookup(value: JsValue): JsLookup = JsLookup(JsDefined(value))
}

/**
  * Represents a Json null value.
  */
case object JsNull extends JsValue

/**
  * Represent a Json boolean value.
  */
case class JsBoolean(value: Boolean) extends JsValue

/**
  * Represent a Json number value.
  */
case class JsNumber(value: BigDecimal) extends JsValue

/**
  * Represent a Json string value.
  */
case class JsString(value: String) extends JsValue

/**
  * Represent a Json array value.
  */
case class JsArray(value: Seq[JsValue] = List()) extends JsValue {

  /**
    * Concatenates this array with the elements of an other array.
    */
  def ++(other: JsArray): JsArray = JsArray(value ++ other.value)

  /**
    * Append an element to this array.
    */
  def :+(el: JsValue): JsArray = JsArray(value :+ el)
  def append(el: JsValue): JsArray = this.:+(el)

  /**
    * Prepend an element to this array.
    */
  def +:(el: JsValue): JsArray = JsArray(el +: value)
  def prepend(el: JsValue): JsArray = this.+:(el)
}

/**
  * Represent a Json object value.
  */
case class JsObject(private val underlying: Map[String, JsValue]) extends JsValue {

  /**
    * The fields of this JsObject in the order passed to to constructor
    */
  lazy val fields: Seq[(String, JsValue)] = underlying.toSeq

  /**
    * The value of this JsObject as an immutable map.
    */
  lazy val value: Map[String, JsValue] = underlying match {
    case m: immutable.Map [String, JsValue] => m
    case m => m.toMap
  }

  /**
    * Return all fields as a set
    */
  def fieldSet: Set[(String, JsValue)] = fields.toSet

  /**
    * Return all keys
    */
  def keys: Set[String] = underlying.keySet

  /**
    * Return all values
    */
  def values: Iterable[JsValue] = underlying.values

  /**
    * Merge this object with another one. Values from other override value of the current object.
    */
  def ++(other: JsObject): JsObject = JsObject(underlying ++ other.underlying)

  /**
    * Removes one field from the JsObject
    */
  def -(otherField: String): JsObject = JsObject(underlying - otherField)

  /**
    * Adds one field to the JsObject
    */
  def +(otherField: (String, JsValue)): JsObject = JsObject(underlying + otherField)

  /**
    * merges everything in depth and doesn't stop at first level, as ++ does
    */
  def deepMerge(other: JsObject): JsObject = {
    def merge(existingObject: JsObject, otherObject: JsObject): JsObject = {
      val result =
        existingObject.underlying ++ otherObject.underlying.map {
          case (otherKey, otherValue) =>
            val maybeExistingValue = existingObject.underlying.get(otherKey)

            val newValue = (maybeExistingValue, otherValue) match {
              case (Some(e: JsObject), o: JsObject) => merge(e, o)
              case (Some(e: JsArray), o: JsArray) => e ++ o
              case _ => otherValue
            }
            otherKey -> newValue
        }
      JsObject(result)
    }
    merge(this, other)
  }

  override def equals(other: Any): Boolean = other match {
    case that: JsObject => (that canEqual this) && fieldSet == that.fieldSet
    case _ => false
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[JsObject]

  override def hashCode: Int = fieldSet.hashCode()
}

object JsObject {

  /**
    * Construct a new JsObject, with the order of fields in the Seq.
    */
  def apply(fields: Seq[(String, JsValue)]): JsObject =
    new JsObject(mutable.LinkedHashMap(fields:_*))
}

// -- Serializers.

private[json] object JsValueSerializer extends JsonSerializer[JsValue] {
  import java.math.{BigDecimal => JBigDec, BigInteger}
  import com.fasterxml.jackson.databind.node.{BigIntegerNode, DecimalNode}

  override def serialize(value: JsValue, json: JsonGenerator, provider: SerializerProvider) {
    value match {
      case JsNumber(v) => {
          // Workaround #3784: Same behaviour as if JsonGenerator were
          // configured with WRITE_BIGDECIMAL_AS_PLAIN, but forced as this
          // configuration is ignored when called from ObjectMapper.valueToTree
          val raw = v.bigDecimal.stripTrailingZeros.toPlainString

          if (raw contains ".") json.writeTree(new DecimalNode(new JBigDec(raw)))
          else json.writeTree(new BigIntegerNode(new BigInteger(raw)))
        }
      case JsString(v) => json.writeString(v)
      case JsBoolean(v) => json.writeBoolean(v)
      case JsArray(elements) => {
          json.writeStartArray()
          elements.foreach { t =>
            serialize(t, json, provider)
          }
          json.writeEndArray()
        }
      case JsObject(values) => {
          json.writeStartObject()
          values.foreach { t =>
            json.writeFieldName(t._1)
            serialize(t._2, json, provider)
          }
          json.writeEndObject()
        }
      case JsNull => json.writeNull()
    }
  }
}

private[json] sealed trait DeserializerContext {
  def addValue(value: JsValue): DeserializerContext
}

private[json] case class ReadingList(content: ListBuffer[JsValue]) extends DeserializerContext {
  override def addValue(value: JsValue): DeserializerContext = {
    ReadingList(content += value)
  }
}

// Context for reading an Object
private[json] case class KeyRead(content: ListBuffer[(String, JsValue)], fieldName: String)
    extends DeserializerContext {
  def addValue(value: JsValue): DeserializerContext = ReadingMap(content += (fieldName -> value))
}

// Context for reading one item of an Object (we already red fieldName)
private[json] case class ReadingMap(content: ListBuffer[(String, JsValue)])
    extends DeserializerContext {

  def setField(fieldName: String) = KeyRead(content, fieldName)
  def addValue(value: JsValue): DeserializerContext =
    throw new Exception("Cannot add a value on an object without a key, malformed JSON object!")
}

private[json] class JsValueDeserializer(factory: TypeFactory, klass: Class[_])
    extends JsonDeserializer[Object] {

  override def isCachable: Boolean = true

  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): JsValue = {
    val value = deserialize(jp, ctxt, List())

    if (!klass.isAssignableFrom(value.getClass)) {
      throw ctxt.mappingException(klass)
    }
    value
  }

  @tailrec
  final def deserialize(jp: JsonParser,
                        ctxt: DeserializationContext,
                        parserContext: List[DeserializerContext]): JsValue = {
    if (jp.getCurrentToken == null) {
      jp.nextToken()
    }

    val (maybeValue, nextContext) = (jp.getCurrentToken.id(): @switch) match {

      case JsonTokenId.ID_NUMBER_INT | JsonTokenId.ID_NUMBER_FLOAT =>
        (Some(JsNumber(jp.getDecimalValue)), parserContext)

      case JsonTokenId.ID_STRING => (Some(JsString(jp.getText)), parserContext)

      case JsonTokenId.ID_TRUE => (Some(JsBoolean(true)), parserContext)

      case JsonTokenId.ID_FALSE => (Some(JsBoolean(false)), parserContext)

      case JsonTokenId.ID_NULL => (Some(JsNull), parserContext)

      case JsonTokenId.ID_START_ARRAY =>
        (None, ReadingList(ListBuffer()) +: parserContext)

      case JsonTokenId.ID_END_ARRAY =>
        parserContext match {
          case ReadingList(content) :: stack => (Some(JsArray(content)), stack)
          case _ =>
            throw new RuntimeException("We should have been reading list, something got wrong")
        }

      case JsonTokenId.ID_START_OBJECT =>
        (None, ReadingMap(ListBuffer()) +: parserContext)

      case JsonTokenId.ID_FIELD_NAME =>
        parserContext match {
          case (c: ReadingMap) :: stack =>
            (None, c.setField(jp.getCurrentName) +: stack)
          case _ =>
            throw new RuntimeException("We should be reading map, something got wrong")
        }

      case JsonTokenId.ID_END_OBJECT =>
        parserContext match {
          case ReadingMap(content) :: stack => (Some(JsObject(content)), stack)
          case _ =>
            throw new RuntimeException(
                "We should have been reading an object, something got wrong")
        }

      case JsonTokenId.ID_NOT_AVAILABLE =>
        throw new RuntimeException("We should have been reading an object, something got wrong")

      case JsonTokenId.ID_EMBEDDED_OBJECT =>
        throw new RuntimeException("We should have been reading an object, something got wrong")
    }

    // Read ahead
    jp.nextToken()

    maybeValue match {
      case Some(v) if nextContext.isEmpty =>
        // done, no more tokens and got a value!
        // note: jp.getCurrentToken == null happens when using treeToValue (we're not parsing tokens)
        v

      case maybeValue =>
        val toPass = maybeValue.map { v =>
          val previous :: stack = nextContext
          (previous.addValue(v)) +: stack
        }.getOrElse(nextContext)

        deserialize(jp, ctxt, toPass)
    }
  }

  // This is used when the root object is null, ie when deserialising "null"
  override def getNullValue = JsNull
}

private[json] class PlayDeserializers(classLoader: ClassLoader) extends Deserializers.Base {
  override def findBeanDeserializer(javaType: JavaType,
                                    config: DeserializationConfig,
                                    beanDesc: BeanDescription) = {
    val klass = javaType.getRawClass
    if (classOf[JsValue].isAssignableFrom(klass) || klass == JsNull.getClass) {
      new JsValueDeserializer(config.getTypeFactory, klass)
    } else null
  }
}

private[json] class PlaySerializers extends Serializers.Base {
  override def findSerializer(config: SerializationConfig,
                              javaType: JavaType,
                              beanDesc: BeanDescription) = {
    val ser: Object =
      if (classOf[JsValue].isAssignableFrom(beanDesc.getBeanClass)) {
        JsValueSerializer
      } else {
        null
      }
    ser.asInstanceOf[JsonSerializer[Object]]
  }
}

private[play] object JacksonJson {
  import com.fasterxml.jackson.core.Version
  import com.fasterxml.jackson.databind.module.SimpleModule
  import com.fasterxml.jackson.databind.Module.SetupContext

  private[this] val classLoader = Thread.currentThread().getContextClassLoader

  def createMapper(): ObjectMapper = (new ObjectMapper).registerModule(module)

  private[json] val mapper = createMapper()

  private[json] object module extends SimpleModule("PlayJson", Version.unknownVersion()) {
    override def setupModule(context: SetupContext) {
      context.addDeserializers(new PlayDeserializers(classLoader))
      context.addSerializers(new PlaySerializers)
    }
  }

  private[this] lazy val jsonFactory = new com.fasterxml.jackson.core.JsonFactory(mapper)

  private[this] def stringJsonGenerator(out: java.io.StringWriter) =
    jsonFactory.createGenerator(out)

  private[this] def jsonParser(c: String): JsonParser = jsonFactory.createParser(c)

  private[this] def jsonParser(data: Array[Byte]): JsonParser = jsonFactory.createParser(data)

  private[this] def jsonParser(stream: InputStream): JsonParser = jsonFactory.createParser(stream)

  def parseJsValue(data: Array[Byte]): JsValue =
    mapper.readValue(jsonParser(data), classOf[JsValue])

  def parseJsValue(input: String): JsValue = mapper.readValue(jsonParser(input), classOf[JsValue])

  def parseJsValue(stream: InputStream): JsValue =
    mapper.readValue(jsonParser(stream), classOf[JsValue])

  def generateFromJsValue(jsValue: JsValue, escapeNonASCII: Boolean = false): String = {
    val sw = new java.io.StringWriter
    val gen = stringJsonGenerator(sw)

    if (escapeNonASCII) {
      gen.enable(JsonGenerator.Feature.ESCAPE_NON_ASCII)
    }

    mapper.writeValue(gen, jsValue)
    sw.flush()
    sw.getBuffer.toString
  }

  def prettyPrint(jsValue: JsValue): String = {
    val sw = new java.io.StringWriter
    val gen = stringJsonGenerator(sw).setPrettyPrinter(
        new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
    )
    val writer: ObjectWriter = mapper.writerWithDefaultPrettyPrinter()
    writer.writeValue(gen, jsValue)
    sw.flush()
    sw.getBuffer.toString
  }

  def jsValueToJsonNode(jsValue: JsValue): JsonNode = mapper.valueToTree(jsValue)

  def jsonNodeToJsValue(jsonNode: JsonNode): JsValue =
    mapper.treeToValue(jsonNode, classOf[JsValue])
}
