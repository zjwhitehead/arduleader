package com.geeksville.dataflash

import scala.io.Source
import scala.collection.mutable.HashMap
import com.geeksville.util.ThreadTools
import com.geeksville.util.AnalyticsService
import java.util.Date
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import com.geeksville.util.Using
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.geeksville.flight.LiveOrPlaybackModel
import java.io.PrintWriter
import com.geeksville.mavlink.AbstractMessage
import java.io.ByteArrayInputStream
import com.geeksville.util.FileTools

class InvalidLogException(reason: String) extends Exception(reason)

trait Element[T] {
  def value: T

  // Try to get this as a double if we can
  def asDouble: Double = throw new Exception("Magic double conversion not supported")
  def asInt: Int = throw new Exception("Magic int conversion not supported")
  def asString: String = throw new Exception("Magic string conversion not supported")

  override def toString = value.toString
}

/// Converts from strings or binary to the appriate native Element
trait ElementConverter {
  def toElement(s: String): Element[_]

  /// @return a tuple with an element and the # of bytes
  def readBinary(in: DataInputStream): (Element[_], Int)

  protected def fromLittleEndian(iIn: Long, numBytes: Int, isUnsigned: Boolean): Long = {
    val i = iIn.toInt
    val wordmask = if (isUnsigned)
      0xffffffffL
    else
      0xffffffffffffffffL

    numBytes match {
      case 8 =>
        val r = fromLittleEndian((iIn >> 32) & wordmask, 4, true) | (fromLittleEndian(iIn & wordmask, 4, isUnsigned) << 32)
        r
      case 4 =>
        (((i & 0xff) << 24) + ((i & 0xff00) << 8) + ((i & 0xff0000) >> 8) + ((i >> 24) & 0xff)) & wordmask
      case 2 =>
        ((i & 0xff) << 8) + ((i & 0xff00) >> 8)
      case 1 =>
        i
    }
  }
}

class LongElement(val value: Long) extends Element[Long] {
  override def asDouble = value
  override def asInt = value.toInt
}
class DoubleElement(val value: Double) extends Element[Double] { override def asDouble = value }
class StringElement(val value: String) extends Element[String] {
  override def asString = value
}

case class IntConverter(reader: DataInputStream => Long, numBytes: Int, isUnsigned: Boolean = false) extends ElementConverter {
  def toElement(s: String) = new LongElement(s.toLong)

  // to convert from little endian (intel is be,
  // return

  def readBinary(in: DataInputStream) = {
    val i = reader(in)
    val n = fromLittleEndian(i, numBytes, isUnsigned)

    (new LongElement(n), numBytes)
  }
}

case class TrueFloatConverter() extends ElementConverter {
  val bytes = new Array[Byte](4)

  def toElement(s: String) = new DoubleElement(s.toDouble)
  def readBinary(in: DataInputStream) = {
    in.read(bytes)
    val n = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat()
    (new DoubleElement(n), 4)
  }
}

case class IntFloatConverter(reader: DataInputStream => Long, numBytes: Int, scale: Double = 1.0, isUnsigned: Boolean = false) extends ElementConverter {
  def toElement(s: String) = new DoubleElement(s.toDouble)
  def readBinary(in: DataInputStream) = {
    val i = reader(in)
    val n = fromLittleEndian(i, numBytes, isUnsigned)
    val d = n * scale
    //println(s"fromle src=$i, le=$n, as double $d")
    (new DoubleElement(d), numBytes)
  }
}

case class StringConverter(numBytes: Int) extends ElementConverter {

  private val buf = new Array[Byte](numBytes)

  def toElement(s: String) = new StringElement(s)
  def readBinary(in: DataInputStream) = {
    in.read(buf)
    val str = buf.takeWhile(_ != 0).map(_.toChar).mkString
    (new StringElement(str), numBytes)
  }
}

/// Describes the formating for a particular message type
/// @param len length of binary packet in bytes - including the three byte header
case class DFFormat(typ: Int, name: String, len: Int, format: String, columns: Seq[String], typeCodes: Map[Char, ElementConverter]) {

  val nameToIndex = Map(columns.zipWithIndex.map { case (name, i) => name -> i }: _*)

  def isFMT = name == "FMT"

  private def converter(typ: Char) = typeCodes.getOrElse(typ, throw new Exception(s"Unknown type code '$typ'"))

  override def toString = s"$name: $format " + columns.mkString(",")

  /// Decode string arguments and generate a message (if possible)
  def createMessage(args: Seq[String]): Option[DFMessage] = {
    val elements = args.zipWithIndex.map {
      case (arg, index) =>
        //println(s"Looking for $index in $this")
        val typ = if (index < format.size)
          format(index) // find the type code letter
        else
          'Z' // If we have too many args passed in, treat the remainder as strings

        //println(s"Using $converter for ${if (index < format.size) columns(index) else "unknown"}/$index=$typ")
        converter(typ).toElement(arg)
    }
    Some(new DFMessage(this, elements))
  }

  /// Decode a binary blob, read ptr at entry is just after the header
  def createBinary(in: DataInputStream): Option[DFMessage] = {
    var totalBytes = 0
    val elements = format.map { f =>
      val conv = converter(f)
      val (elem, numBytes) = conv.readBinary(in)
      totalBytes += numBytes
      elem
    }

    // Check that we got the right amount of payload
    val expectedBytes = len - 3
    if (totalBytes < expectedBytes) {
      // println(s"packet too short for $this")
      // It seems that if the packet ends early, the next packet is immediately after - no need to skip
      // in.skipBytes(expectedBytes - totalBytes)
    }

    // if (totalBytes > expectedBytes) println(s"Error packet too long for $name")

    val r = new DFMessage(this, elements)
    //println(s"Returning msg $r")
    Some(r)
  }
}

object DFFormat {
}

/// A dataflash message
case class DFMessage(fmt: DFFormat, elements: Seq[Element[_]]) extends AbstractMessage {
  def fieldNames = fmt.columns
  def asPairs = fieldNames.zip(elements)

  private def getElement(name: String) = fmt.nameToIndex.get(name).map(elements(_))
  def getOpt[T](name: String) = getElement(name).map(_.asInstanceOf[Element[T]].value)
  def getOptDouble(name: String) = getElement(name).map(_.asDouble)
  def get[T](name: String) = getOpt[T](name).get

  /// Generate a string that would be a valid text log line (machine and human readable)
  override def toString = {
    s"$messageType, " + elements.mkString(", ")
  }

  override def fields: Map[String, Any] = Map(fmt.columns.zip(elements.map(_.value)): _*)

  /// The typename
  override def messageType: String = fmt.name

  // Syntatic sugar

  // CMD
  def ctotOpt = getOpt[Long]("CTot")
  def cnumOpt = getOpt[Long]("CNum")
  def cidOpt = getOpt[Long]("CId")
  def prm1Opt = getOptDouble("Prm1")
  def prm2Opt = getOptDouble("Prm2")
  def prm3Opt = getOptDouble("Prm3")
  def prm4Opt = getOptDouble("Prm4")

  // GPS
  def latOpt = getOpt[Double]("Lat")
  def lngOpt = getOpt[Double]("Lng").orElse(getOpt[Double]("Lon"))
  def altOpt = getOpt[Double]("Alt")
  def spdOpt = getOpt[Double]("Spd")
  def weekOpt = getOpt[Long]("Week")
  def tOpt = getOpt[Long]("T")
  def gpsTimeOpt = getOpt[Long]("GPSTime") // PX4 format

  /// Return time in usecs since 1970
  def gpsTimeUsec = {

    // An APM format time message
    var r = weekOpt.flatMap { week =>
      timeMSopt.flatMap { time =>
        if (week == 0 || week > 3000) // Reject lines where the time is clearly invalid gtr than year 2024ish
          None // No lock yet
        else {
          // Returns seconds since 1970
          def gpsTimeToTime(week: Long, sec: Double) = {
            val epoch = 86400 * (10 * 365 + (1980 - 1969) / 4 + 1 + 6 - 2)
            epoch + 86400 * 7 * week + sec - 15
          }

          val t = gpsTimeToTime(week, time * 0.001)

          // println(s"GPS week=$week, time=$time, date=" + new Date((t * 1e3).toLong))

          Some((t * 1e6).toLong)
        }
      }
    }

    // A PX4 style time message?
    if (!r.isDefined) {
      r = gpsTimeOpt.flatMap { gtime =>
        Some(gtime)
      }
    }

    //println(s"Return date $r")
    r
  }

  // CURR
  def thrOut = get[Long]("ThrOut")

  // MODE
  def mode = get[String]("Mode")

  // PARM
  def name = get[String]("Name")
  def value = get[Double]("Value")

  // MSG
  def message = get[String]("Message")

  // NTUN
  def arspdOpt = getOpt[Double]("Arspd")

  // TIME
  def startTimeOpt = getOpt[Long]("StartTime")

  // VER
  def archOpt = getOpt[String]("Arch")
  def fwGitOpt = getOpt[String]("FwGit")

  def timeMSopt = getOpt[Long]("TimeMS")

  // ERR
  def eCode = get[Byte]("ECode")
  def subsys = get[Byte]("Subsys")
}

object DFMessage {
  final val GPS = "GPS"
  final val PARM = "PARM"
  final val MODE = "MODE"
  final val ATT = "ATT"
  final val IMU = "IMU"
  final val CMD = "CMD"
  final val NTUN = "NTUN"
  final val MSG = "MSG"
  final val VER = "VER"
  final val TIME = "TIME"
  final val ERR = "ERR"
}

class DFReader {
  import DFMessage._

  private val textToFormat = HashMap[String, DFFormat]()
  private val typToFormat = HashMap[Int, DFFormat]()

  var messages: Iterable[DFMessage] = Iterable.empty

  private case class ModeConverter() extends ElementConverter {
    def toElement(s: String) = new StringElement(s)
    def readBinary(in: DataInputStream) = {
      import LiveOrPlaybackModel._

      val mode = in.readByte()

      val modeToCodeMap = buildName.getOrElse("") match {
        case "ArduPlane" => planeCodeToModeMap
        case "ArduCopter" => copterCodeToModeMap
        case "ArduRover" => roverCodeToModeMap
        case _ => Map[Int, String]()
      }

      val str = modeToCodeMap.getOrElse(mode, s"mode$mode")
      (new StringElement(str), 1)
    }
  }

  /*
Format characters in the format string for binary log messages
  b   : int8_t
  B   : uint8_t
  h   : int16_t
  H   : uint16_t
  i   : int32_t
  I   : uint32_t
  f   : float
  n   : char[4]
  N   : char[16]
  Z   : char[64]
  c   : int16_t * 100
  C   : uint16_t * 100
  e   : int32_t * 100
  E   : uint32_t * 100
  L   : int32_t latitude/longitude
  M   : uint8_t flight mode
 */

  private val typeCodes = Map[Char, ElementConverter](
    'b' -> IntConverter(_.readByte(), 1),
    'B' -> IntConverter(_.readUnsignedByte(), 1, isUnsigned = true),
    'h' -> IntConverter(_.readShort(), 2),
    'H' -> IntConverter(_.readUnsignedShort(), 2, isUnsigned = true),
    'i' -> IntConverter(_.readInt(), 4),
    'I' -> IntConverter(_.readInt().toLong & 0xffffffff, 4, isUnsigned = true),
    'f' -> TrueFloatConverter(),
    'n' -> StringConverter(4),
    'N' -> StringConverter(16),
    'Z' -> StringConverter(64),
    'c' -> IntFloatConverter(_.readShort(), 2, 0.01),
    'C' -> IntFloatConverter(_.readUnsignedShort(), 2, 0.01, isUnsigned = true),
    'e' -> IntFloatConverter(_.readInt(), 4, 0.01),
    'E' -> IntFloatConverter(_.readInt().toLong & 0xffffffff, 4, 0.01, isUnsigned = true),

    // FIXME - misconverts -73 as 355
    'L' -> IntFloatConverter(_.readInt(), 4, 1.0e-7),
    'M' -> ModeConverter(),
    'q' -> IntConverter(_.readLong(), 8),
    'Q' -> IntConverter(_.readLong(), 8, isUnsigned = true))

  /// ArduCopter etc...
  private var buildName: Option[String] = None

  /// We initially only understand FMT message, we learn the rest
  Seq {
    DFFormat(0x80, "FMT", 89, "BBnNZ", Seq("Type", "Length", "Name", "Format", "Columns"), typeCodes)
  }.foreach(addFormat)

  def addFormat(f: DFFormat) {
    textToFormat(f.name) = f
    typToFormat(f.typ) = f
  }

  def tryParseLine(s: String): Option[DFMessage] = {
    // println(s"Parsing $s")
    try { // This line could be malformated in many different ways
      val splits = s.split(',').map(_.trim)
      /*
        * FMT, 128, 89, FMT, BBnNZ, Type,Length,Name,Format
        * FMT, 129, 23, PARM, Nf, Name,Value
*/
      if (splits.length >= 2) {
        val typ = splits(0)
        textToFormat.get(typ) match {
          case None =>
            println(s"Unrecognized format: $typ")
            None
          case Some(fmt) =>
            val args = splits.tail
            if (args.size < fmt.columns.size) {
              println("Not enough elements - line probably corrupted")
              None
            } else {
              // If it is a new format type, then add it
              if (fmt.isFMT) {
                // Example: FMT, 129, 23, PARM, Nf, Name,Value
                val newfmt = DFFormat(args(0).toInt, args(2), args(1).toInt, args(3), args.drop(4), typeCodes)
                //println(s"Adding new format: $newfmt")
                addFormat(newfmt)
              }

              fmt.createMessage(args)
            }

        }
      } else
        None
    } catch {
      case ex: Exception =>
        println(s"Malformed log: $s", ex)
        None
    }
  }

  /// When scanning the file we look for certain message types and treat them specially
  def filterMessage(msg: DFMessage) {
    msg.messageType match {
      case MSG =>
        LiveOrPlaybackModel.decodeVersionMessage(msg.message).foreach { m =>
          buildName = Some(m._1)
        }

      case _ =>
    }
  }

  ///we just map from source to records - so callers can read lazily
  def parseText(inSrc: Source) {
    messages = new Iterable[DFMessage] {
      override def iterator = {
        var hasSeenFMT = false
        val in = inSrc.reset() // Rewind to the beginning for each new read

        in.getLines.zipWithIndex.flatMap {
          case (l, i) =>
            if (i > 100 && !hasSeenFMT)
              throw new InvalidLogException("This doesn't seem to be a dataflash log")

            val msgOpt = tryParseLine(l)
            msgOpt.foreach { msg =>
              hasSeenFMT |= msg.fmt.isFMT

              filterMessage(msg)
            }
            msgOpt
        }
      }
    }
  }

  private def warn(s: String) {
    println(s)
  }

  /// Emit my messages as a text format log file
  def toText(out: PrintWriter) {
    // FIXME - generate a real header
    val header = Seq("37", "", "ArduCopter V3.1.5 (ee63c88b)", "Free RAM: 65535", "PX4")
    header.foreach(out.println(_))

    messages.foreach { m =>
      //println(m)
      out.println(m)
    }
    warn("Completed converting bin to log")
  }

  def parseBinary(bytes: Array[Byte]) {
    messages = new Iterable[DFMessage] {

      // We open a new stream for each iterator
      override def iterator = new Iterator[DFMessage] {
        // We use EOFException to terminate
        private val in = new DataInputStream(new ByteArrayInputStream(bytes))
        private var closed = false

        private var numRead = 0
        private var hasSeenFMT = false

        // Read next valid DFForamt
        private def getHeader(): Option[DFFormat] = {
          numRead += 1

          if (in.readByte() != 0xa3.toByte) {
            warn("Bad header1 byte")
            None
          } else if (in.readByte() != 0x95.toByte) {
            warn("Bad header2 byte")
            None
          } else {
            val code = in.readByte().toInt & 0xff
            //println(s"Looking for format $code")
            typToFormat.get(code)
          }
        }

        private def getMessage() = {
          try {
            val r = getHeader().flatMap(_.createBinary(in))

            r.foreach { msg =>
              hasSeenFMT |= msg.fmt.isFMT

              // If it is a new format type, then add it
              if (msg.fmt.isFMT) {
                val elements = msg.elements
                val colnames = elements(4).asString.split(',')

                val newfmt = DFFormat(elements(0).asInt, elements(2).asString, elements(0).asInt, elements(3).asString, colnames, typeCodes)
                // println(s"Adding new format: $newfmt")
                addFormat(newfmt)
              }

              filterMessage(msg)
            }

            if (numRead > 20 && !hasSeenFMT)
              throw new InvalidLogException("This doesn't seem to be a dataflash log")

            r
          } catch {
            case ex: EOFException =>
              in.close()
              closed = true
              None
            case ex: Exception =>
              println(s"Malformed binary log? $ex")
              None
          }
        }

        private var msgopt: Option[DFMessage] = None

        def hasNext = {
          while (!msgopt.isDefined && !closed)
            msgopt = getMessage()

          msgopt.isDefined
        }

        def next = {
          val r = msgopt.orNull
          msgopt = None
          r
        }
      }
    }
  }
}

object DFReader {
  def main(args: Array[String]) {
    val reader = new DFReader

    // FIXME - this leaks file descriptors
    val filename = "/home/kevinh/tmp/px4.bin"
    reader.parseBinary(FileTools.toByteArray(new BufferedInputStream(new FileInputStream(filename))))
    for (line <- reader.messages) {
      println(line)
      if (line.messageType == "GPS")
        println(line.gpsTimeUsec)
    }
  }
}
