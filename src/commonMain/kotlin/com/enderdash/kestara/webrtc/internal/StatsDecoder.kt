package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.DataChannelStats
import com.enderdash.kestara.webrtc.IceCandidatePairStats
import com.enderdash.kestara.webrtc.IceCandidateStats
import com.enderdash.kestara.webrtc.PeerConnectionStats
import com.enderdash.kestara.webrtc.TransportStats
import com.enderdash.kestara.webrtc.WebRtcException
import kotlin.time.Instant

internal object StatsDecoder {
  private const val FORMAT_VERSION = 1

  fun decode(data: ByteArray): PeerConnectionStats = try {
    val input = BigEndianReader(data)
    val version = input.readInt()
    if (version != FORMAT_VERSION) {
      throw WebRtcException("Unsupported native stats format: $version")
    }
    val timestamp = Instant.fromEpochMilliseconds(input.readLong())
    val opened = input.readLong()
    val closed = input.readLong()
    val transport = TransportStats(
      packetsSent = input.readLong(),
      packetsReceived = input.readLong(),
      bytesSent = input.readLong(),
      bytesReceived = input.readLong(),
      iceRole = input.readString(),
      iceState = input.readString(),
      dtlsRole = input.readString(),
      dtlsState = input.readString(),
      tlsVersion = input.readString(),
      dtlsCipher = input.readString(),
      selectedCandidatePairChanges = input.readInt().toUInt().toLong(),
      selectedCandidatePair = if (input.readBoolean()) input.readPair() else null,
    )
    val channelCount = input.readInt()
    require(channelCount in 0..65_535) { "Invalid native DataChannel stats count" }
    val channels = List(channelCount) {
      DataChannelStats(
        identifier = input.readShort().toUShort().toInt(),
        label = input.readString(),
        protocol = input.readString(),
        state = input.readString(),
        messagesSent = input.readInt().toUInt().toLong(),
        bytesSent = input.readLong(),
        messagesReceived = input.readInt().toUInt().toLong(),
        bytesReceived = input.readLong(),
      )
    }
    require(input.remaining == 0) { "Native stats contain trailing data" }
    PeerConnectionStats(timestamp, opened, closed, transport, channels)
  } catch (error: WebRtcException) {
    throw error
  } catch (error: Exception) {
    throw WebRtcException("Failed to decode native WebRTC stats", error)
  }

  private fun BigEndianReader.readPair(): IceCandidatePairStats = IceCandidatePairStats(
    id = readString(),
    localCandidate = readCandidate(),
    remoteCandidate = readCandidate(),
    packetsSent = readLong(),
    packetsReceived = readLong(),
    bytesSent = readLong(),
    bytesReceived = readLong(),
    currentRoundTripTimeSeconds = Double.fromBits(readLong()),
    totalRoundTripTimeSeconds = Double.fromBits(readLong()),
    requestsSent = readLong(),
    requestsReceived = readLong(),
    responsesSent = readLong(),
    responsesReceived = readLong(),
    state = readString(),
    nominated = readBoolean(),
  )

  private fun BigEndianReader.readCandidate(): IceCandidateStats = IceCandidateStats(
    id = readString(),
    address = readString(),
    port = readShort().toUShort().toInt(),
    protocol = readString(),
    candidateType = readString(),
    priority = readInt().toUInt().toLong(),
    url = readString(),
    relayProtocol = readString(),
    foundation = readString(),
    relatedAddress = readString(),
    relatedPort = readShort().toUShort().toInt(),
    usernameFragment = readString(),
    tcpType = readString(),
  )
}

private class BigEndianReader(private val data: ByteArray) {
  private var offset = 0
  val remaining: Int get() = data.size - offset

  fun readBoolean(): Boolean = readByte().toInt() != 0

  fun readShort(): Short {
    requireRemaining(2)
    val result = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    offset += 2
    return result.toShort()
  }

  fun readInt(): Int {
    requireRemaining(4)
    var result = 0
    repeat(4) { result = (result shl 8) or (data[offset++].toInt() and 0xff) }
    return result
  }

  fun readLong(): Long {
    requireRemaining(8)
    var result = 0L
    repeat(8) { result = (result shl 8) or (data[offset++].toLong() and 0xff) }
    return result
  }

  fun readString(): String {
    val size = readInt()
    require(size >= 0 && size <= remaining) { "Invalid native stats string length" }
    val result = data.decodeToString(offset, offset + size)
    offset += size
    return result
  }

  private fun readByte(): Byte {
    requireRemaining(1)
    return data[offset++]
  }

  private fun requireRemaining(count: Int) {
    require(count <= remaining) { "Native stats ended unexpectedly" }
  }
}
