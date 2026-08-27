package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.WebRtcException

internal object NativeEventDecoder {
  fun decode(data: ByteArray): NativeEvent {
    val input = LittleEndianReader(data)
    val version = input.byte()
    if (version != 1) throw WebRtcException("Unsupported native event format: $version")
    val event = NativeEvent(
      kind = input.int(),
      peerHandle = input.long(),
      channelHandle = input.long(),
      operationHandle = input.long(),
      number = input.int(),
      text = input.optionalBytes()?.decodeToString(),
      secondaryText = input.optionalBytes()?.decodeToString(),
      data = input.optionalBytes(),
    )
    if (input.remaining != 0) throw WebRtcException("Native event contains trailing data")
    return event
  }
}

private class LittleEndianReader(private val data: ByteArray) {
  private var offset = 0
  val remaining: Int get() = data.size - offset

  fun byte(): Int {
    requireAvailable(1)
    return data[offset++].toInt() and 0xff
  }

  fun int(): Int {
    requireAvailable(4)
    var result = 0
    repeat(4) { index -> result = result or ((data[offset++].toInt() and 0xff) shl (index * 8)) }
    return result
  }

  fun long(): Long {
    requireAvailable(8)
    var result = 0L
    repeat(8) { index -> result = result or ((data[offset++].toLong() and 0xff) shl (index * 8)) }
    return result
  }

  fun optionalBytes(): ByteArray? {
    val length = int()
    if (length == -1) return null
    require(length >= 0) { "Invalid native event payload length" }
    requireAvailable(length)
    return data.copyOfRange(offset, offset + length).also { offset += length }
  }

  private fun requireAvailable(count: Int) {
    require(count <= remaining) { "Native event ended unexpectedly" }
  }
}
