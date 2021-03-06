package com.github.plokhotnyuk.jsoniter_scala

import java.io.InputStream

import com.github.plokhotnyuk.jsoniter_scala.JsonReader._

import scala.annotation.{switch, tailrec}
import scala.util.control.NonFatal

class JsonParseException(msg: String, cause: Throwable, withStackTrace: Boolean)
  extends RuntimeException(msg, cause, true, withStackTrace)

// Use an option of throwing stack-less exception for cases when parse exceptions can be not exceptional,
// see more details here: https://shipilev.net/blog/2014/exceptional-performance/
case class ReaderConfig(
    throwParseExceptionWithStackTrace: Boolean = true,
    appendHexDumpToParseException: Boolean = true,
    preferredBufSize: Int = 16384,
    preferredCharBufSize: Int = 16384)

final class JsonReader private[jsoniter_scala](
    private var buf: Array[Byte] = new Array[Byte](4096),
    private var head: Int = 0,
    private var tail: Int = 0,
    private var mark: Int = -1,
    private var charBuf: Array[Char] = new Array[Char](4096),
    private var in: InputStream = null,
    private var totalRead: Int = 0,
    private var config: ReaderConfig = new ReaderConfig) {
  def requiredKeyError(reqFields: Array[String], reqs: Int*): Nothing = {
    val len = reqFields.length
    var i = 0
    var j = 0
    while (j < len) {
      if ((reqs(j >> 5) & (1 << j)) != 0) {
        i = appendString(if (i == 0) "missing required field(s) \"" else "\", \"", i)
        i = appendString(reqFields(j), i)
      }
      j += 1
    }
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def unexpectedKeyError(len: Int): Nothing = {
    var i = prependString("unexpected field: \"", len)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def discriminatorValueError(discriminatorFieldName: String): Nothing = {
    var i = appendString("illegal value of discriminator field \"", 0)
    i = appendString(discriminatorFieldName, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def enumValueError(value: String): Nothing = {
    var i = appendString("illegal enum value: \"", 0)
    i = appendString(value, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  def setMark(): Unit = mark = head

  @tailrec
  def scanToKey(s: String): Unit = if (!isCharBufEqualsTo(readKeyAsCharBuf(), s)) {
    skip()
    if (isNextToken(',', head)) scanToKey(s)
    else reqFieldError(s)
  }

  def rollbackToMark(): Unit = {
    head = mark
    mark = -1
  }

  def readKeyAsCharBuf(): Int = {
    readParentheses()
    val x = parseString(0, charBuf.length, head)
    readColon()
    x
  }

  def readValueAsCharBuf(): Int =
    if (isNextToken('"', head)) parseString(0, charBuf.length, head)
    else decodeError("expected string value")

  def readKeyAsString(): String = {
    readParentheses()
    val len = parseString(0, charBuf.length, head)
    readColon()
    new String(charBuf, 0, len)
  }

  def readKeyAsBoolean(): Boolean = {
    readParentheses()
    val x = parseBoolean(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsByte(): Byte = {
    readParentheses()
    val x = parseByte(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsChar(): Char = {
    readParentheses()
    val x = parseChar(head)
    readParenthesesWithColon()
    x
  }

  def readKeyAsShort(): Short = {
    readParentheses()
    val x = parseShort(isToken = false)
    readParenthesesWithColon()
    x.toShort
  }

  def readKeyAsInt(): Int = {
    readParentheses()
    val x = parseInt(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsLong(): Long = {
    readParentheses()
    val x = parseLong(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsFloat(): Float = {
    readParentheses()
    val x = parseFloat(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsDouble(): Double = {
    readParentheses()
    val x = parseDouble(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsBigInt(): BigInt = {
    readParentheses()
    val x = parseBigInt(isToken = false)
    readParenthesesWithColon()
    x
  }

  def readKeyAsBigDecimal(): BigDecimal = {
    readParentheses()
    val x = parseBigDecimal(isToken = false, null)
    readParenthesesWithColon()
    x
  }

  def readByte(): Byte = parseByte(isToken = true)

  def readChar(): Char = {
    readParentheses()
    val x = parseChar(head)
    readParentheses()
    x
  }

  def readShort(): Short = parseShort(isToken = true)

  def readInt(): Int = parseInt(isToken = true)

  def readLong(): Long = parseLong(isToken = true)

  def readDouble(): Double = parseDouble(isToken = true)

  def readFloat(): Float = parseFloat(isToken = true)

  def readBigInt(default: BigInt): BigInt = parseBigInt(isToken = true, default)

  def readBigDecimal(default: BigDecimal): BigDecimal = parseBigDecimal(isToken = true, default)

  def readString(default: String = null): String =
    if (isNextToken('"', head)) {
      val len = parseString(0, charBuf.length, head)
      new String(charBuf, 0, len)
    } else if (isCurrentToken('n', head)) parseNull(default, head)
    else decodeError("expected string value or null")

  def readBoolean(): Boolean = parseBoolean(isToken = true)

  def readNull[A](default: A): A =
    if (isNextToken('n', head)) parseNull(default, head)
    else nullError(head)

  def readNullOrTokenError[A](default: A, b: Byte): A =
    if (isCurrentToken('n', head)) parseNull(default, head)
    else tokenOrNullError(b)

  def nextToken(): Byte = nextToken(head)

  def isNextToken(b: Byte): Boolean = isNextToken(b, head)

  def isCurrentToken(b: Byte): Boolean = isCurrentToken(b, head)

  def rollbackToken(): Unit = {
    val pos = head
    if (pos == 0) throw new ArrayIndexOutOfBoundsException("expected preceding call of 'nextToken()' or 'isNextToken()'")
    head = pos - 1
  }

  def charBufToHashCode(len: Int): Int = toHashCode(charBuf, len)

  def isCharBufEqualsTo(len: Int, s: String): Boolean = len == s.length && isCharBufEqualsTo(len, s, 0)

  def skip(): Unit = head = {
    val b = nextToken(head)
    if (b == '"') skipString(evenBackSlashes = true, head)
    else if ((b >= '0' && b <= '9') || b == '-') skipNumber(head)
    else if (b == 'n' || b == 't') skipFixedBytes(3, head)
    else if (b == 'f') skipFixedBytes(4, head)
    else if (b == '{') skipNested('{', '}', 0, head)
    else if (b == '[') skipNested('[', ']', 0, head)
    else decodeError("expected value", head - 1)
  }

  def arrayStartError(): Nothing = decodeError("expected '[' or null")

  def arrayEndError(): Nothing = decodeError("expected ']' or ','")

  def objectStartError(): Nothing = decodeError("expected '{' or null")

  def objectEndError(): Nothing = decodeError("expected '}' or ','")

  def decodeError(msg: String): Nothing = decodeError(msg, head - 1)

  private def tokenOrNullError(b: Byte): Nothing = {
    var i = appendString("expected '", 0)
    charBuf(i) = b.toChar
    i = appendString("' or null", i + 1)
    decodeError(i, head - 1, null)
  }

  private def reqFieldError(s: String): Nothing = {
    var i = appendString("missing required field \"", 0)
    i = appendString(s, i)
    i = appendString("\"", i)
    decodeError(i, head - 1, null)
  }

  private def decodeError(msg: String, pos: Int, cause: Throwable = null): Nothing =
    decodeError(appendString(msg, 0), pos, cause)

  private def decodeError(from: Int, pos: Int, cause: Throwable) = {
    var i = appendString(", offset: 0x", from)
    val offset = if (in eq null) 0 else totalRead - tail
    i = appendHex(offset + pos, i) // TODO: consider support of offset values beyond 2Gb
    if (config.appendHexDumpToParseException) {
      i = appendString(", buf:", i)
      i = appendHexDump(Math.max((pos - 32) & -16, 0), Math.min((pos + 48) & -16, tail), offset, i)
    }
    throw new JsonParseException(new String(charBuf, 0, i), cause, config.throwParseExceptionWithStackTrace)
  }

  @tailrec
  private def nextByte(pos: Int): Byte =
    if (pos < tail) {
      head = pos + 1
      buf(pos)
    } else nextByte(loadMoreOrError(pos))

  @tailrec
  private def nextToken(pos: Int): Byte =
    if (pos < tail) {
      val b = buf(pos)
      if (b == ' ' || b == '\n' || b == '\t' || b == '\r') nextToken(pos + 1)
      else {
        head = pos + 1
        b
      }
    } else nextToken(loadMoreOrError(pos))

  @tailrec
  private def isNextToken(t: Byte, pos: Int): Boolean =
    if (pos < tail) {
      val b = buf(pos)
      if (b == t) {
        head = pos + 1
        true
      } else if (b == ' ' || b == '\n' || b == '\t' || b == '\r') isNextToken(t, pos + 1)
      else {
        head = pos + 1
        false
      }
    } else isNextToken(t, loadMoreOrError(pos))

  private def isCurrentToken(b: Byte, pos: Int): Boolean =
    if (pos == 0) throw new ArrayIndexOutOfBoundsException("expected preceding call of 'nextToken()' or 'isNextToken()'")
    else buf(pos - 1) == b

  @tailrec
  private def parseNull[A](default: A, pos: Int): A =
    if (pos + 2 < tail) {
      if (buf(pos) != 'u') nullError(pos)
      else if (buf(pos + 1) != 'l') nullError(pos + 1)
      else if (buf(pos + 2) != 'l') nullError(pos + 2)
      else {
        head = pos + 3
        default
      }
    } else parseNull(default, loadMoreOrError(pos))

  private def nullError(pos: Int) = decodeError("expected value or null", pos)

  @tailrec
  private def isCharBufEqualsTo(len: Int, s: String, i: Int): Boolean =
    if (i == len) true
    else if (charBuf(i) != s.charAt(i)) false
    else isCharBufEqualsTo(len, s, i + 1)

  private def appendString(s: String, from: Int): Int = {
    val lim = from + s.length
    if (lim > charBuf.length) growCharBuf(lim)
    var i = from
    while (i < lim) {
      charBuf(i) = s.charAt(i - from)
      i += 1
    }
    i
  }

  private def prependString(s: String, from: Int): Int = {
    val len = s.length
    val lim = from + len
    if (lim > charBuf.length) growCharBuf(lim)
    var i = lim - 1
    while (i >= len) {
      charBuf(i) = charBuf(i - len)
      i -= 1
    }
    i = 0
    while (i < len) {
      charBuf(i) = s.charAt(i)
      i += 1
    }
    lim
  }

  private def readParentheses(): Unit = if (!isNextToken('"', head)) decodeError("expected '\"'")

  private def readParenthesesWithColon(): Unit =
    if (nextByte(head) != '"') decodeError("expected '\"'")
    else readColon()

  private def readColon(): Unit = if (!isNextToken(':', head)) decodeError("expected ':'")

  private def parseBoolean(isToken: Boolean): Boolean =
    (if (isToken) nextToken(head) else nextByte(head): @switch) match {
      case 't' => parseTrue(head)
      case 'f' => parseFalse(head)
      case _ => booleanError(head - 1)
    }

  @tailrec
  private def parseTrue(pos: Int): Boolean =
    if (pos + 2 < tail) {
      if (buf(pos) != 'r') booleanError(pos)
      else if (buf(pos + 1) != 'u') booleanError(pos + 1)
      else if (buf(pos + 2) != 'e') booleanError(pos + 2)
      else {
        head = pos + 3
        true
      }
    } else parseTrue(loadMoreOrError(pos))

  @tailrec
  private def parseFalse(pos: Int): Boolean =
    if (pos + 3 < tail) {
      if (buf(pos) != 'a') booleanError(pos)
      else if (buf(pos + 1) != 'l') booleanError(pos + 1)
      else if (buf(pos + 2) != 's') booleanError(pos + 2)
      else if (buf(pos + 3) != 'e') booleanError(pos + 3)
      else {
        head = pos + 4
        false
      }
    } else parseFalse(loadMoreOrError(pos))

  private def booleanError(pos: Int): Nothing = decodeError("illegal boolean", pos)

  // TODO: consider fast path with unrolled loop
  private def parseByte(isToken: Boolean): Byte = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var v = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (v == 0) leadingZeroError(pos - 1)
        v = v * 10 + ('0' - b)
        if (v < Byte.MinValue) byteOverflowError(pos)
        pos + 1
      }
      head = pos
      if (negative) v.toByte
      else if (v == Byte.MinValue) byteOverflowError(pos - 1)
      else (-v).toByte
    } else numberError(pos - 1)
  }

  // TODO: consider fast path with unrolled loop
  private def parseShort(isToken: Boolean): Short = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var v = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (v == 0) leadingZeroError(pos - 1)
        v = v * 10 + ('0' - b)
        if (v < Short.MinValue) shortOverflowError(pos)
        pos + 1
      }
      head = pos
      if (negative) v.toShort
      else if (v == Short.MinValue) shortOverflowError(pos - 1)
      else (-v).toShort
    } else numberError(pos - 1)
  }

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseInt(isToken: Boolean): Int = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var v = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (v == 0) leadingZeroError(pos - 1)
        if (v < -214748364) intOverflowError(pos)
        v = v * 10 + ('0' - b)
        if (v >= 0) intOverflowError(pos)
        pos + 1
      }
      head = pos
      if (negative) v
      else if (v == Int.MinValue) intOverflowError(pos - 1)
      else -v
    } else numberError(pos - 1)
  }

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseLong(isToken: Boolean): Long = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    val negative = b == '-'
    if (negative) b = nextByte(head)
    var pos = head
    if (b >= '0' && b <= '9') {
      var v: Long = '0' - b
      while ((pos < tail || {
        pos = loadMore(pos)
        pos < tail
      }) && {
        b = buf(pos)
        b >= '0' && b <= '9'
      }) pos = {
        if (v == 0) leadingZeroError(pos - 1)
        if (v < -922337203685477580L) longOverflowError(pos)
        v = v * 10 + ('0' - b)
        if (v >= 0) longOverflowError(pos)
        pos + 1
      }
      head = pos
      if (negative) v
      else if (v == Long.MinValue) longOverflowError(pos - 1)
      else -v
    } else numberError(pos - 1)
  }

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseDouble(isToken: Boolean): Double = {
    var posMan = 0L
    var manExp = 0
    var posExp = 0
    var isNeg = false
    var isExpNeg = false
    var isZeroFirst = false
    var lim = charBuf.length
    var i = 0
    var state = 0
    var pos = head
    while (pos < tail || {
      pos = loadMore(pos)
      pos < tail
    }) {
      if (i >= lim) lim = growCharBuf(i + 1)
      val ch = buf(pos).toChar
      (state: @switch) match {
        case 0 => // start
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            if (!isToken) numberError(pos)
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            isNeg = true
            state = 2
          } else numberError(pos)
        case 1 => // whitespaces
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            isNeg = true
            state = 2
          } else numberError(pos)
        case 2 => // signum
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else numberError(pos)
        case 3 => // first int digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = posMan * 10 + (ch - '0')
            if (isZeroFirst) leadingZeroError(pos - 1)
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 4 => // int digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 922337203685477580L) posMan = posMan * 10 + (ch - '0')
            else manExp += 1
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 5 => // dot
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 922337203685477580L) {
              posMan = posMan * 10 + (ch - '0')
              manExp -= 1
            }
            state = 6
          } else numberError(pos)
        case 6 => // frac digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 922337203685477580L) {
              posMan = posMan * 10 + (ch - '0')
              manExp -= 1
            }
            state = 6
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 7 => // e char
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = ch - '0'
            state = 9
          } else if (ch == '-' || ch == '+') {
            charBuf(i) = ch
            i += 1
            isExpNeg = ch == '-'
            state = 8
          } else numberError(pos)
        case 8 => // exp. sign
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = ch - '0'
            state = 9
          } else numberError(pos)
        case 9 => // exp. digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = posExp * 10 + (ch - '0')
            state = if (Math.abs(toExp(manExp, isExpNeg, posExp)) > 350) 10 else 9
          } else {
            head = pos
            return toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 10 => // exp. digit overflow
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 10
          } else {
            head = pos
            return toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
      }
      pos += 1
    }
    head = pos
    if (state == 3 || state == 4 || state == 6 || state == 9) toDouble(isNeg, posMan, manExp, isExpNeg, posExp, i)
    else if (state == 10) toExpOverflowDouble(isNeg, posMan, manExp, isExpNeg, posExp)
    else numberError(pos)
  }

  private def toDouble(isNeg: Boolean, posMan: Long, manExp: Int, isExpNeg: Boolean, posExp: Int, i: Int): Double =
    if (posMan <= 999999999999999L) { // max mantissa that can be converted w/o rounding error by double mult or div
      val man = if (isNeg) -posMan else posMan
      val exp = toExp(manExp, isExpNeg, posExp)
      if (exp == 0) man
      else {
        val maxExp = pow10d.length
        if (exp > -maxExp && exp < 0) man / pow10d(-exp)
        else if (exp > 0 && exp < maxExp) man * pow10d(exp)
        else toDouble(i)
      }
    } else toDouble(i)

  private def toDouble(i: Int): Double = java.lang.Double.parseDouble(new String(charBuf, 0, i))

  private def toExpOverflowDouble(isNeg: Boolean, posMan: Long, manExp: Int, isExpNeg: Boolean, posExp: Int): Double =
    if (toExp(manExp, isExpNeg, posExp) > 0 && posMan != 0) {
      if (isNeg) Double.NegativeInfinity else Double.PositiveInfinity
    } else {
      if (isNeg) -0.0 else 0.0
    }

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseFloat(isToken: Boolean): Float = {
    var posMan = 0
    var manExp = 0
    var posExp = 0
    var isNeg = false
    var isExpNeg = false
    var isZeroFirst = false
    var lim = charBuf.length
    var i = 0
    var state = 0
    var pos = head
    while (pos < tail || {
      pos = loadMore(pos)
      pos < tail
    }) {
      if (i >= lim) lim = growCharBuf(i + 1)
      val ch = buf(pos).toChar
      (state: @switch) match {
        case 0 => // start
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            if (!isToken) numberError(pos)
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            isNeg = true
            state = 2
          } else numberError(pos)
        case 1 => // whitespaces
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            isNeg = true
            state = 2
          } else numberError(pos)
        case 2 => // signum
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = ch - '0'
            isZeroFirst = posMan == 0
            state = 3
          } else numberError(pos)
        case 3 => // first int digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posMan = posMan * 10 + (ch - '0')
            if (isZeroFirst) leadingZeroError(pos - 1)
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 4 => // int digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 214748364) posMan = posMan * 10 + (ch - '0')
            else manExp += 1
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 5 => // dot
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 214748364) {
              posMan = posMan * 10 + (ch - '0')
              manExp -= 1
            }
            state = 6
          } else numberError(pos)
        case 6 => // frac digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            if (posMan < 214748364) {
              posMan = posMan * 10 + (ch - '0')
              manExp -= 1
            }
            state = 6
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 7 => // e char
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = ch - '0'
            state = 9
          } else if (ch == '-' || ch == '+') {
            charBuf(i) = ch
            i += 1
            isExpNeg = ch == '-'
            state = 8
          } else numberError(pos)
        case 8 => // exp. sign
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = ch - '0'
            state = 9
          } else numberError(pos)
        case 9 => // exp. digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            posExp = posExp * 10 + (ch - '0')
            state = if (Math.abs(toExp(manExp, isExpNeg, posExp)) > 55) 10 else 9
          } else {
            head = pos
            return toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
        case 10 => // exp. digit overflow
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 10
          } else {
            head = pos
            return toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
          }
      }
      pos += 1
    }
    head = pos
    if (state == 3 || state == 4 || state == 6 || state == 9) toFloat(isNeg, posMan, manExp, isExpNeg, posExp, i)
    else if (state == 10) toExpOverflowFloat(isNeg, posMan, manExp, isExpNeg, posExp)
    else numberError(pos)
  }

  private def toFloat(isNeg: Boolean, posMan: Int, manExp: Int, isExpNeg: Boolean, posExp: Int, i: Int): Float = {
    val man = if (isNeg) -posMan else posMan
    val exp = toExp(manExp, isExpNeg, posExp)
    if (posMan <= 99999999) { // max mantissa that can be converted w/o rounding error by float mult or div
      if (exp == 0) man
      else {
        val maxFloatExp = pow10f.length
        if (exp > -maxFloatExp && exp < 0) man / pow10f(-exp)
        else if (exp > 0 && exp < maxFloatExp) man * pow10f(exp)
        else { // using double mult or div instead of two float mults with greater rounding error
          val maxDoubleExp = pow10d.length
          if (exp > -maxDoubleExp && exp < 0) (man / pow10d(-exp)).toFloat
          else if (exp > 0 && exp < maxDoubleExp) (man * pow10d(exp)).toFloat
          else toFloat(i)
        }
      }
    } else toFloat(i)
  }

  private def toFloat(i: Int): Float = java.lang.Float.parseFloat(new String(charBuf, 0, i))

  private def toExpOverflowFloat(isNeg: Boolean, posMan: Int, manExp: Int, isExpNeg: Boolean, posExp: Int): Float =
    if (toExp(manExp, isExpNeg, posExp) > 0 && posMan != 0) {
      if (isNeg) Float.NegativeInfinity else Float.PositiveInfinity
    } else {
      if (isNeg) -0.0f else 0.0f
    }

  private def toExp(manExp: Int, isExpNeg: Boolean, exp: Int): Int = manExp + (if (isExpNeg) -exp else exp)

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseBigInt(isToken: Boolean, default: BigInt = null): BigInt = {
    var b = if (isToken) nextToken(head) else nextByte(head)
    if (b == 'n') {
      if (isToken) parseNull(default, head)
      else numberError(head)
    } else {
      var lim = if (2 > charBuf.length) growCharBuf(2) else charBuf.length
      var i = 0
      val negative = b == '-'
      if (negative) {
        charBuf(i) = b.toChar
        i += 1
        b = nextByte(head)
      }
      var pos = head
      if (b >= '0' && b <= '9') {
        val isZeroFirst = b == '0'
        charBuf(i) = b.toChar
        i += 1
        while ((pos < tail || {
          pos = loadMore(pos)
          pos < tail
        }) && {
          b = buf(pos)
          b >= '0' && b <= '9'
        }) pos = {
          if (isZeroFirst ) leadingZeroError(pos - 1)
          if (i >= lim) lim = growCharBuf(i + 1)
          charBuf(i) = b.toChar
          i += 1
          pos + 1
        }
        head = pos
        new BigInt(new java.math.BigDecimal(charBuf, 0, i).toBigInteger)
      } else numberError(pos - 1)
    }
  }

  // TODO: consider fast path with unrolled loop for small numbers
  private def parseBigDecimal(isToken: Boolean, default: BigDecimal): BigDecimal = {
    var isZeroFirst = false
    var lim = charBuf.length
    var i = 0
    var state = 0
    var pos = head
    while (pos < tail || {
      pos = loadMore(pos)
      pos < tail
    }) {
      if (i >= lim) lim = growCharBuf(i + 1)
      val ch = buf(pos).toChar
      (state: @switch) match {
        case 0 => // start
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            if (!isToken) numberError(pos)
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            isZeroFirst = ch == '0'
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            state = 2
          } else if (ch == 'n' && isToken) {
            state = 10
          } else numberError(pos)
        case 1 => // whitespaces
          if (ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') {
            state = 1
          } else if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            isZeroFirst = ch == '0'
            state = 3
          } else if (ch == '-') {
            charBuf(i) = ch
            i += 1
            state = 2
          } else if (ch == 'n' && isToken) {
            state = 10
          } else numberError(pos)
        case 2 => // signum
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            isZeroFirst = ch == '0'
            state = 3
          } else numberError(pos)
        case 3 => // first int digit
          if (ch >= '0' && ch <= '9') {
            if (isZeroFirst) leadingZeroError(pos - 1)
            charBuf(i) = ch
            i += 1
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 4 => // int digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 4
          } else if (ch == '.') {
            charBuf(i) = ch
            i += 1
            state = 5
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 5 => // dot
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 6
          } else numberError(pos)
        case 6 => // frac digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 6
          } else if (ch == 'e' || ch == 'E') {
            charBuf(i) = ch
            i += 1
            state = 7
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 7 => // e char
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 9
          } else if (ch == '-' || ch == '+') {
            charBuf(i) = ch
            i += 1
            state = 8
          } else numberError(pos)
        case 8 => // exp. sign
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 9
          } else numberError(pos)
        case 9 => // exp. digit
          if (ch >= '0' && ch <= '9') {
            charBuf(i) = ch
            i += 1
            state = 9
          } else {
            head = pos
            return toBigDecimal(i)
          }
        case 10 => // n'u'll
          if (ch == 'u') state = 11
          else numberError(pos)
        case 11 => // nu'l'l
          if (ch == 'l') state = 12
          else numberError(pos)
        case 12 => // nul'l'
          if (ch == 'l') state = 13
          else numberError(pos)
        case 13 => // null
          head = pos
          return default
      }
      pos += 1
    }
    head = pos
    if (state == 3 || state == 4 || state == 6 || state == 9) toBigDecimal(i)
    else if (state == 13) default
    else numberError(pos)
  }

  private def toBigDecimal(len: Int): BigDecimal = new BigDecimal(new java.math.BigDecimal(charBuf, 0, len))

  private def numberError(pos: Int): Nothing = decodeError("illegal number", pos)

  private def leadingZeroError(pos: Int): Nothing = decodeError("illegal number with leading zero", pos)

  private def byteOverflowError(pos: Int): Nothing = decodeError("value is too large for byte", pos)

  private def shortOverflowError(pos: Int): Nothing = decodeError("value is too large for short", pos)

  private def intOverflowError(pos: Int): Nothing = decodeError("value is too large for int", pos)

  private def longOverflowError(pos: Int): Nothing = decodeError("value is too large for long", pos)

  @tailrec
  private def parseString(i: Int, lim: Int, pos: Int): Int =
    if (i >= lim) parseString(i, growCharBuf(i + 1), pos)
    else if (pos < tail) {
      val b = buf(pos)
      if (b == '"') {
        head = pos + 1
        i
      } else if ((b ^ '\\') < 1) parseEncodedString(i, lim, pos)
      else {
        charBuf(i) = b.toChar
        parseString(i + 1, lim, pos + 1)
      }
    } else parseString(i, lim, loadMoreOrError(pos))

  @tailrec
  private def parseEncodedString(i: Int, lim: Int, pos: Int): Int =
    if (i >= lim) parseEncodedString(i, growCharBuf(i + 2), pos) // 2 is length of surrogate pair
    else {
      val remaining = tail - pos
      if (remaining > 0) {
        val b1 = buf(pos)
        if (b1 >= 0) { // 1 byte, 7 bits: 0xxxxxxx
          if (b1 == '"') {
            head = pos + 1
            i
          } else if (b1 != '\\') {
            charBuf(i) = b1.toChar
            parseEncodedString(i + 1, lim, pos + 1)
          } else if (remaining > 1) {
            (buf(pos + 1): @switch) match {
              case '"' =>
                charBuf(i) = '"'
                parseEncodedString(i + 1, lim, pos + 2)
              case 'n' =>
                charBuf(i) = '\n'
                parseEncodedString(i + 1, lim, pos + 2)
              case 'r' =>
                charBuf(i) = '\r'
                parseEncodedString(i + 1, lim, pos + 2)
              case 't' =>
                charBuf(i) = '\t'
                parseEncodedString(i + 1, lim, pos + 2)
              case 'b' =>
                charBuf(i) = '\b'
                parseEncodedString(i + 1, lim, pos + 2)
              case 'f' =>
                charBuf(i) = '\f'
                parseEncodedString(i + 1, lim, pos + 2)
              case '\\' =>
                charBuf(i) = '\\'
                parseEncodedString(i + 1, lim, pos + 2)
              case '/' =>
                charBuf(i) = '/'
                parseEncodedString(i + 1, lim, pos + 2)
              case 'u' =>
                if (remaining > 5) {
                  val ch1 = readEscapedUnicode(pos + 2)
                  if (ch1 < 2048) {
                    charBuf(i) = ch1
                    parseEncodedString(i + 1, lim, pos + 6)
                  } else if (!Character.isHighSurrogate(ch1)) {
                    if (Character.isLowSurrogate(ch1)) decodeError("expected high surrogate character", pos + 5)
                    charBuf(i) = ch1
                    parseEncodedString(i + 1, lim, pos + 6)
                  } else if (remaining > 11) {
                    if (buf(pos + 6) == '\\') {
                      if (buf(pos + 7) == 'u') {
                        val ch2 = readEscapedUnicode(pos + 8)
                        if (!Character.isLowSurrogate(ch2)) decodeError("expected low surrogate character", pos + 11)
                        charBuf(i) = ch1
                        charBuf(i + 1) = ch2
                        parseEncodedString(i + 2, lim, pos + 12)
                      } else illegalEscapeSequenceError(pos + 7)
                    } else illegalEscapeSequenceError(pos + 6)
                  } else parseEncodedString(i, lim, loadMoreOrError(pos))
                } else parseEncodedString(i, lim, loadMoreOrError(pos))
              case _ => illegalEscapeSequenceError(pos + 1)
            }
          } else parseEncodedString(i, lim, loadMoreOrError(pos))
        } else if ((b1 >> 5) == -2) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
          if (remaining > 1) {
            val b2 = buf(pos + 1)
            if (isMalformed2(b1, b2)) malformedBytes(b1, b2, pos)
            charBuf(i) = ((b1 << 6) ^ b2 ^ 0xF80).toChar // 0xF80 == ((0xC0.toByte << 6) ^ 0x80.toByte)
            parseEncodedString(i + 1, lim, pos + 2)
          } else parseEncodedString(i, lim, loadMoreOrError(pos))
        } else if ((b1 >> 4) == -2) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
          if (remaining > 2) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val ch = ((b1 << 12) ^ (b2 << 6) ^ b3 ^ 0xFFFE1F80).toChar // 0xFFFE1F80 == ((0xE0.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
            if (isMalformed3(b1, b2, b3) || Character.isSurrogate(ch)) malformedBytes(b1, b2, b3, pos)
            charBuf(i) = ch
            parseEncodedString(i + 1, lim, pos + 3)
          } else parseEncodedString(i, lim, loadMoreOrError(pos))
        } else if ((b1 >> 3) == -2) { // 4 bytes, 21 bits: 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
          if (remaining > 3) {
            val b2 = buf(pos + 1)
            val b3 = buf(pos + 2)
            val b4 = buf(pos + 3)
            val cp = (b1 << 18) ^ (b2 << 12) ^ (b3 << 6) ^ b4 ^ 0x381F80 // 0x381F80 == ((0xF0.toByte << 18) ^ (0x80.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
            if (isMalformed4(b2, b3, b4) || !Character.isSupplementaryCodePoint(cp)) malformedBytes(b1, b2, b3, b4, pos)
            charBuf(i) = Character.highSurrogate(cp)
            charBuf(i + 1) = Character.lowSurrogate(cp)
            parseEncodedString(i + 2, lim, pos + 4)
          } else parseEncodedString(i, lim, loadMoreOrError(pos))
        } else malformedBytes(b1, pos)
      } else parseEncodedString(i, lim, loadMoreOrError(pos))
    }

  @tailrec
  private def parseChar(pos: Int): Char = {
    val remaining = tail - pos
    if (remaining > 0) {
      val b1 = buf(pos)
      if (b1 >= 0) { // 1 byte, 7 bits: 0xxxxxxx
        if (b1 == '"') decodeError("illegal value for char")
        else if (b1 != '\\') {
          head = pos + 1
          b1.toChar
        } else if (remaining > 1) {
          (buf(pos + 1): @switch) match {
            case 'b' =>
              head = pos + 2
              '\b'
            case 'f' =>
              head = pos + 2
              '\f'
            case 'n' =>
              head = pos + 2
              '\n'
            case 'r' =>
              head = pos + 2
              '\r'
            case 't' =>
              head = pos + 2
              '\t'
            case '"' =>
              head = pos + 2
              '"'
            case '/' =>
              head = pos + 2
              '/'
            case '\\' =>
              head = pos + 2
              '\\'
            case 'u' =>
              if (remaining > 5) {
                val ch1 = readEscapedUnicode(pos + 2)
                if (Character.isSurrogate(ch1)) decodeError("illegal surrogate character", pos + 5)
                head = pos + 6
                ch1
              } else parseChar(loadMoreOrError(pos))
            case _ => illegalEscapeSequenceError(pos + 1)
          }
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 5) == -2) { // 2 bytes, 11 bits: 110xxxxx 10xxxxxx
        if (remaining > 1) {
          val b2 = buf(pos + 1)
          if (isMalformed2(b1, b2)) malformedBytes(b1, b2, pos)
          head = pos + 2
          ((b1 << 6) ^ b2 ^ 0xF80).toChar // 0xF80 == ((0xC0.toByte << 6) ^ 0x80.toByte)
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 4) == -2) { // 3 bytes, 16 bits: 1110xxxx 10xxxxxx 10xxxxxx
        if (remaining > 2) {
          val b2 = buf(pos + 1)
          val b3 = buf(pos + 2)
          val ch = ((b1 << 12) ^ (b2 << 6) ^ b3 ^ 0xFFFE1F80).toChar // 0xFFFE1F80 == ((0xE0.toByte << 12) ^ (0x80.toByte << 6) ^ 0x80.toByte)
          if (isMalformed3(b1, b2, b3) || Character.isSurrogate(ch)) malformedBytes(b1, b2, b3, pos)
          head = pos + 3
          ch
        } else parseChar(loadMoreOrError(pos))
      } else if ((b1 >> 3) == -2) decodeError("illegal surrogate character", pos + 3)
      else malformedBytes(b1, pos)
    } else parseChar(loadMoreOrError(pos))
  }

  private def readEscapedUnicode(pos: Int): Char =
    ((fromHexDigit(pos) << 12) + (fromHexDigit(pos + 1) << 8) + (fromHexDigit(pos + 2) << 4) + fromHexDigit(pos + 3)).toChar

  private def fromHexDigit(pos: Int): Int = {
    val b = buf(pos)
    if (b >= '0' && b <= '9') b - 48
    else {
      val b1 = b & -33
      if (b1 >= 'A' && b1 <= 'F') b1 - 55
      else decodeError("expected hex digit", pos)
    }
  }

  private def isMalformed2(b1: Byte, b2: Byte): Boolean =
    (b1 & 0x1E) == 0 || (b2 & 0xC0) != 0x80

  private def isMalformed3(b1: Byte, b2: Byte, b3: Byte): Boolean =
    (b1 == 0xE0.toByte && (b2 & 0xE0) == 0x80) || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80

  private def isMalformed4(b2: Byte, b3: Byte, b4: Byte): Boolean =
    (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || (b4 & 0xC0) != 0x80

  private def illegalEscapeSequenceError(pos: Int): Nothing = decodeError("illegal escape sequence", pos)

  private def malformedBytes(b1: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    decodeError(i, pos, null)
  }

  private def malformedBytes(b1: Byte, b2: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    decodeError(i, pos + 1, null)
  }

  private def malformedBytes(b1: Byte, b2: Byte, b3: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    i = appendString(", 0x", i)
    i = appendHex(b3, i)
    decodeError(i, pos + 2, null)
  }

  private def malformedBytes(b1: Byte, b2: Byte, b3: Byte, b4: Byte, pos: Int): Nothing = {
    var i = appendString("malformed byte(s): 0x", 0)
    i = appendHex(b1, i)
    i = appendString(", 0x", i)
    i = appendHex(b2, i)
    i = appendString(", 0x", i)
    i = appendHex(b3, i)
    i = appendString(", 0x", i)
    i = appendHex(b4, i)
    decodeError(i, pos + 3, null)
  }

  private def appendHexDump(start: Int, end: Int, offset: Int, from: Int): Int = {
    val alignedAbsFrom = (start + offset) & -16
    val alignedAbsTo = (end + offset + 15) & -16
    val len = alignedAbsTo - alignedAbsFrom
    val bufOffset = alignedAbsFrom - offset
    var i = appendString(dumpHeader, from)
    i = appendString(dumpBorder, i)
    var lim = charBuf.length
    var j = 0
    while (j < len) {
      val linePos = j & 15
      if (linePos == 0) {
        if (i + 81 >= lim) lim = growCharBuf(i + 81) // 81 == dumpBorder.length
        charBuf(i) = '\n'
        charBuf(i + 1) = '|'
        charBuf(i + 2) = ' '
        putHex(alignedAbsFrom + j, i + 3)
        charBuf(i + 11) = ' '
        charBuf(i + 12) = '|'
        charBuf(i + 13) = ' '
        i += 14
      }
      val pos = bufOffset + j
      charBuf(i + 50 - (linePos << 1)) =
        if (pos >= start && pos < end) {
          val b = buf(pos)
          putHex(b, i)
          charBuf(i + 2) = ' '
          if (b <= 31 || b >= 127) '.' else b.toChar
        } else {
          charBuf(i) = ' '
          charBuf(i + 1) = ' '
          charBuf(i + 2) = ' '
          ' '
        }
      i += 3
      if (linePos == 15) {
        charBuf(i) = '|'
        charBuf(i + 1) = ' '
        charBuf(i + 18) = ' '
        charBuf(i + 19) = '|'
        i += 20
      }
      j += 1
    }
    appendString(dumpBorder, i)
  }

  private def appendHex(d: Int, i: Int): Int = {
    if (i + 8 >= charBuf.length) growCharBuf(i + 8)
    putHex(d, i)
    i + 8
  }

  private def putHex(d: Int, i: Int): Unit = {
    charBuf(i) = toHexDigit(d >>> 28)
    charBuf(i + 1) = toHexDigit(d >>> 24)
    charBuf(i + 2) = toHexDigit(d >>> 20)
    charBuf(i + 3) = toHexDigit(d >>> 16)
    charBuf(i + 4) = toHexDigit(d >>> 12)
    charBuf(i + 5) = toHexDigit(d >>> 8)
    charBuf(i + 6) = toHexDigit(d >>> 4)
    charBuf(i + 7) = toHexDigit(d)
  }

  private def appendHex(b: Byte, i: Int): Int = {
    if (i + 2 >= charBuf.length) growCharBuf(i + 2)
    putHex(b, i)
    i + 2
  }

  private def putHex(b: Byte, i: Int): Unit = {
    charBuf(i) = toHexDigit(b >>> 4)
    charBuf(i + 1) = toHexDigit(b)
  }

  private def toHexDigit(n: Int): Char = {
    val nibble = n & 15
    (((9 - nibble) >> 31) & 39) + nibble + 48 // branchless conversion of nibble to hex digit
  }.toChar

  private def growCharBuf(required: Int): Int = {
    val lim = charBuf.length
    val newLim = Math.max(lim << 1, required)
    val cs = new Array[Char](newLim)
    System.arraycopy(charBuf, 0, cs, 0, lim)
    charBuf = cs
    newLim
  }

  @tailrec
  private def skipString(evenBackSlashes: Boolean, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"' && evenBackSlashes) pos + 1
      else if (b == '\\') skipString(!evenBackSlashes, pos + 1)
      else skipString(evenBackSlashes = true, pos + 1)
    } else skipString(evenBackSlashes, loadMoreOrError(pos))

  @tailrec
  private def skipNumber(pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if ((b >= '0' && b <= '9') || b == '.' || b == '-' || b == '+' || b == 'e' || b == 'E') skipNumber(pos + 1)
      else pos
    } else skipNumber(loadMoreOrError(pos))

  @tailrec
  private def skipNested(opening: Byte, closing: Byte, level: Int, pos: Int): Int =
    if (pos < tail) {
      val b = buf(pos)
      if (b == '"') skipNested(opening, closing, level, skipString(evenBackSlashes = true, pos + 1))
      else if (b == closing) {
        if (level == 0) pos + 1
        else skipNested(opening, closing, level - 1, pos + 1)
      } else if (b == opening) skipNested(opening, closing, level + 1, pos + 1)
      else skipNested(opening, closing, level, pos + 1)
    } else skipNested(opening, closing, level, loadMoreOrError(pos))

  @tailrec
  private def skipFixedBytes(n: Int, pos: Int): Int = {
    val newPos = pos + n
    val diff = newPos - tail
    if (diff <= 0) newPos
    else skipFixedBytes(diff, loadMoreOrError(pos))
  }

  private def loadMoreOrError(pos: Int): Int =
    if (in eq null) endOfInput()
    else {
      val minPos = ensureBufCapacity(pos)
      val n = externalRead(tail, buf.length - tail)
      if (n > 0) {
        tail += n
        totalRead += n
        pos - minPos
      } else endOfInput()
    }

  private def loadMore(pos: Int): Int =
    if (in eq null) pos
    else {
      val minPos = ensureBufCapacity(pos)
      val n = externalRead(tail, buf.length - tail)
      if (n > 0) {
        tail += n
        totalRead += n
      }
      pos - minPos
    }

  private def ensureBufCapacity(pos: Int): Int = {
    val minPos = if (mark >= 0) mark else pos
    if (minPos > 0) {
      val remaining = tail - minPos
      if (remaining > 0) {
        System.arraycopy(buf, minPos, buf, 0, remaining)
        mark -= (if (mark >= 0) minPos else 0)
      }
      tail = remaining
    } else if (tail > 0) {
      val bs = new Array[Byte](buf.length << 1)
      System.arraycopy(buf, 0, bs, 0, buf.length)
      buf = bs
    }
    minPos
  }

  private def externalRead(from: Int, len: Int): Int = try in.read(buf, from, len) catch {
    case NonFatal(ex) => endOfInput(ex)
  }

  private def endOfInput(cause: Throwable = null): Nothing = decodeError("unexpected end of input", tail, cause)

  private def freeTooLongBuf(): Unit =
    if (buf.length > config.preferredBufSize) buf = new Array[Byte](config.preferredBufSize)

  private def freeTooLongCharBuf(): Unit =
    if (charBuf.length > config.preferredCharBufSize) charBuf = new Array[Char](config.preferredCharBufSize)
}

object JsonReader {
  private val pool: ThreadLocal[JsonReader] = new ThreadLocal[JsonReader] {
    override def initialValue(): JsonReader = new JsonReader
  }
  private val defaultConfig = ReaderConfig()
  private val pow10f: Array[Float] = // all powers of 10 that can be represented exactly in float
    Array(1f, 1e+1f, 1e+2f, 1e+3f, 1e+4f, 1e+5f, 1e+6f, 1e+7f, 1e+8f, 1e+9f, 1e+10f)
  private val pow10d: Array[Double] = // all powers of 10 that can be represented exactly in double
    Array(1, 1e+1, 1e+2, 1e+3, 1e+4, 1e+5, 1e+6, 1e+7, 1e+8, 1e+9, 1e+10, 1e+11,
      1e+12, 1e+13, 1e+14, 1e+15, 1e+16, 1e+17, 1e+18, 1e+19, 1e+20, 1e+21, 1e+22)
  private val dumpHeader =
    "\n           +-------------------------------------------------+" +
    "\n           |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |"
  private val dumpBorder =
    "\n+----------+-------------------------------------------------+------------------+"

  /**
    * Deserialize JSON content encoded in UTF-8 from an input stream into a value of given `A` type 
    * with default parsing options that maximize description of error. 
    * 
    * Use custom configuration to turn on racing of stack less exceptions and/or turn off a hex dump printing 
    * to the error message. 
    *
    * @param codec a codec for the given `A` type
    * @param in the input stream to parse from
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also if a low-level I/O problem (unexpected end of input, network error) occurs
    *                            while some input bytes are expected
    * @throws NullPointerException If the `codec` or `in` is null.
    */
  final def read[A](codec: JsonCodec[A], in: InputStream): A = read(codec, in, defaultConfig)

  /**
    * Deserialize JSON content encoded in UTF-8 from an input stream into a value of given `A` type.
    *
    * @param codec a codec for the given `A` type
    * @param in the input stream to parse from
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also if a low-level I/O problem (unexpected end-of-input, network error) occurs
    *                            while some input bytes are expected
    * @throws NullPointerException if the `codec`, `in` or `config` is null
    */
  final def read[A](codec: JsonCodec[A], in: InputStream, config: ReaderConfig): A = {
    if ((in eq null) || (config eq null)) throw new NullPointerException
    val reader = pool.get
    reader.config = config
    reader.in = in
    reader.head = 0
    reader.tail = 0
    reader.mark = -1
    reader.totalRead = 0
    try codec.decode(reader, codec.nullValue) // also checks that `codec` is not null before any parsing
    finally {
      reader.in = null  // to help GC, and to avoid modifying of supplied for parsing Array[Byte]
      reader.freeTooLongBuf()
      reader.freeTooLongCharBuf()
    }
  }

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type
    * with default parsing options that maximize description of error. 
    *
    * Use custom configuration to turn on racing of stack less exceptions and/or turn off a hex dump printing 
    * to the error message. 
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException If the `codec` or `buf` is null.
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte]): A = read(codec, buf, defaultConfig)

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type
    * with specified parsing options.
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException if the `codec`, `buf` or `config` is null
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte], config: ReaderConfig): A =
    read(codec, buf, 0, buf.length, config)

  /**
    * Deserialize JSON content encoded in UTF-8 from a byte array into a value of given `A` type with
    * specified parsing options or with defaults that maximize description of error. 
    *
    * @param codec a codec for the given `A` type
    * @param buf the byte array to parse from
    * @param from the start position of the provided byte array
    * @param to the position of end of input in the provided byte array
    * @param config a parsing configuration
    * @tparam A type of the value to parse
    * @return a successfully parsed value
    * @throws JsonParseException if underlying input contains malformed UTF-8 bytes, invalid JSON content or
    *                            the input JSON structure does not match structure that expected for result type,
    *                            also in case if end of input is detected while some input bytes are expected
    * @throws NullPointerException if the `codec`, `buf` or `config` is null
    * @throws ArrayIndexOutOfBoundsException if the `to` is greater than `buf` length or negative,
    *                                        or `from` is greater than `to` or negative
    */
  final def read[A](codec: JsonCodec[A], buf: Array[Byte], from: Int, to: Int, config: ReaderConfig = defaultConfig): A = {
    if (config eq null) throw new NullPointerException
    if (to > buf.length || to < 0) // also checks that `buf` is not null before any parsing
      throw new ArrayIndexOutOfBoundsException("`to` should be positive and not greater than `buf` length")
    if (from > to || from < 0)
      throw new ArrayIndexOutOfBoundsException("`from` should be positive and not greater than `to`")
    val reader = pool.get
    val currBuf = reader.buf
    reader.config = config
    reader.buf = buf
    reader.head = from
    reader.tail = to
    reader.mark = -1
    reader.totalRead = 0
    try codec.decode(reader, codec.nullValue) // also checks that `codec` is not null before any parsing
    finally {
      reader.buf = currBuf
      reader.freeTooLongCharBuf()
    }
  }

  final def toHashCode(cs: Array[Char], len: Int): Int = {
    var i = 0
    var h = 777767777
    while (i < len) {
      h = (h ^ cs(i)) * 1500450271
      h ^= (h >>> 21) // mix highest bits to reduce probability of zeroing and loosing part of hash from preceding chars
      i += 1
    }
    h
  }
}