@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.enderdash.kestara.webrtc.internal

import com.enderdash.kestara.webrtc.DataChannelOptions
import com.enderdash.kestara.webrtc.DtlsCertificate
import com.enderdash.kestara.webrtc.PeerConnectionConfiguration
import com.enderdash.kestara.webrtc.SessionDescription
import com.enderdash.kestara.webrtc.SessionDescriptionType
import com.enderdash.kestara.webrtc.WebRtcException
import com.enderdash.kestara.webrtc.WebRtcRuntimeOptions
import com.enderdash.kestara.webrtc.native.kestara_abi_version
import com.enderdash.kestara.webrtc.native.kestara_bytes
import com.enderdash.kestara.webrtc.native.kestara_bytes_free
import com.enderdash.kestara.webrtc.native.kestara_library_version
import com.enderdash.kestara.webrtc.native.kestara_runtime_certificate_fingerprint
import com.enderdash.kestara.webrtc.native.kestara_runtime_certificate_pem
import com.enderdash.kestara.webrtc.native.kestara_runtime_create
import com.enderdash.kestara.webrtc.native.kestara_runtime_poll
import com.enderdash.kestara.webrtc.native.kestara_runtime_release
import com.enderdash.kestara.webrtc.native.kestara_runtime_submit
import com.enderdash.kestara.webrtc.native.kestara_runtime_wake
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlin.time.Duration

internal actual fun platformNativeBridge(): NativeBridge = NativeNativeBridge

private object NativeNativeBridge : NativeBridge {
  override val abiVersion: Int get() = kestara_abi_version()
  override val libraryVersion: String get() = ownedBytes(kestara_library_version()).decodeToString()

  override fun createRuntime(options: WebRtcRuntimeOptions): Long = memScoped {
    val output = alloc<ULongVar>()
    val error = emptyOutput()
    NativeInput(NativeWire.runtime(options)).use { configuration ->
      checkStatus(
        kestara_runtime_create(
          configuration.pointer,
          configuration.length,
          output.ptr,
          error.ptr,
        ),
        error,
      )
    }
    output.value.toLong()
  }

  override fun runtimeCertificateFingerprint(runtime: Long): String =
    outputString { output, error ->
      kestara_runtime_certificate_fingerprint(runtime.toULong(), output, error)
    }

  override fun runtimeCertificatePem(runtime: Long): String = outputString { output, error ->
    kestara_runtime_certificate_pem(runtime.toULong(), output, error)
  }

  override fun submitCreatePeer(
    runtime: Long,
    operation: Long,
    configuration: PeerConnectionConfiguration,
  ) = submit(
    runtime = runtime,
    operation = operation,
    command = CREATE_PEER,
    timeout = configuration.operationTimeout,
    configuration = NativeWire.peer(configuration),
  )

  override fun submitRestartIce(runtime: Long, operation: Long, peer: Long, timeout: Duration) =
    submit(runtime, operation, RESTART_ICE, peer, timeout)

  override fun submitSetConfiguration(
    runtime: Long,
    operation: Long,
    peer: Long,
    configuration: PeerConnectionConfiguration,
    timeout: Duration,
  ) = submit(
    runtime, operation, SET_CONFIGURATION, peer, timeout,
    configuration = NativeWire.peer(configuration),
  )

  override fun submitCreateDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    type: SessionDescriptionType,
    timeout: Duration,
  ) = submit(runtime, operation, CREATE_DESCRIPTION, peer, timeout, number = type.ordinal.toLong())

  override fun submitSetLocalDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) = submit(
    runtime, operation, SET_LOCAL_DESCRIPTION, peer, timeout,
    text = description.sdp,
    number = description.type.ordinal.toLong(),
  )

  override fun submitSetRemoteDescription(
    runtime: Long,
    operation: Long,
    peer: Long,
    description: SessionDescription,
    timeout: Duration,
  ) = submit(
    runtime, operation, SET_REMOTE_DESCRIPTION, peer, timeout,
    text = description.sdp,
    number = description.type.ordinal.toLong(),
  )

  override fun submitAddIceCandidate(
    runtime: Long,
    operation: Long,
    peer: Long,
    candidate: String,
    sdpMid: String?,
    sdpMLineIndex: Int?,
    timeout: Duration,
  ) = submit(
    runtime, operation, ADD_ICE_CANDIDATE, peer, timeout,
    text = candidate,
    secondaryText = sdpMid,
    number = (sdpMLineIndex ?: -1).toLong(),
  )

  override fun submitCreateDataChannel(
    runtime: Long,
    operation: Long,
    peer: Long,
    label: String,
    options: DataChannelOptions,
    timeout: Duration,
  ) = submit(
    runtime, operation, CREATE_DATA_CHANNEL, peer, timeout,
    text = label,
    configuration = NativeWire.dataChannel(options),
  )

  override fun submitSendText(
    runtime: Long,
    operation: Long,
    channel: Long,
    text: String,
    timeout: Duration,
    trySend: Boolean,
  ) = submit(
    runtime, operation, if (trySend) TRY_SEND_TEXT else SEND_TEXT, channel, timeout, text = text,
  )

  override fun submitSendBinary(
    runtime: Long,
    operation: Long,
    channel: Long,
    data: ByteArray,
    timeout: Duration,
    trySend: Boolean,
  ) = submit(
    runtime, operation, if (trySend) TRY_SEND_BINARY else SEND_BINARY, channel, timeout, data = data,
  )

  override fun submitDataChannelWritable(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) = submit(runtime, operation, DATA_CHANNEL_WRITABLE, channel, timeout)

  override fun submitDataChannelOutstandingBytes(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) = submit(runtime, operation, DATA_CHANNEL_OUTSTANDING_BYTES, channel, timeout)

  override fun submitSetDataChannelThresholds(
    runtime: Long,
    operation: Long,
    channel: Long,
    low: Long,
    high: Long,
    timeout: Duration,
  ) = submit(
    runtime, operation, SET_DATA_CHANNEL_THRESHOLDS, channel, timeout,
    number = low,
    secondaryNumber = high,
  )

  override fun submitGetStats(runtime: Long, operation: Long, peer: Long, timeout: Duration) =
    submit(runtime, operation, GET_STATS, peer, timeout)

  override fun submitRotateCertificate(
    runtime: Long,
    operation: Long,
    certificate: DtlsCertificate?,
    timeout: Duration,
  ) = submit(runtime, operation, ROTATE_CERTIFICATE, timeout = timeout, text = certificate?.pem)

  override fun submitCloseDataChannel(
    runtime: Long,
    operation: Long,
    channel: Long,
    timeout: Duration,
  ) = submit(runtime, operation, CLOSE_DATA_CHANNEL, channel, timeout)

  override fun submitClosePeer(runtime: Long, operation: Long, peer: Long, timeout: Duration) =
    submit(runtime, operation, CLOSE_PEER, peer, timeout)

  override fun submitCloseRuntime(runtime: Long, operation: Long, timeout: Duration) =
    submit(runtime, operation, CLOSE_RUNTIME, timeout = timeout)

  override fun pollRuntimeEvent(runtime: Long, timeout: Duration): NativeEvent? = memScoped {
    val output = emptyOutput()
    val error = emptyOutput()
    checkStatus(
      kestara_runtime_poll(
        runtime.toULong(),
        timeout.inWholeMilliseconds,
        output.ptr,
        error.ptr,
      ),
      error,
    )
    val bytes = takeOwnedBytes(output)
    if (bytes.isEmpty()) null else NativeEventDecoder.decode(bytes)
  }

  override fun wakeRuntime(runtime: Long): Unit = callWithError { error ->
    kestara_runtime_wake(runtime.toULong(), error)
  }

  override fun releaseRuntime(runtime: Long): Unit = callWithError { error ->
    kestara_runtime_release(runtime.toULong(), error)
  }

  private fun submit(
    runtime: Long,
    operation: Long,
    command: Int,
    target: Long = 0,
    timeout: Duration,
    text: String? = null,
    secondaryText: String? = null,
    number: Long = 0,
    secondaryNumber: Long = 0,
    data: ByteArray = ByteArray(0),
    configuration: ByteArray = ByteArray(0),
  ) {
    NativeInput(text?.encodeToByteArray(), nullable = true).use { textInput ->
      NativeInput(secondaryText?.encodeToByteArray(), nullable = true).use { secondaryInput ->
        NativeInput(data).use { dataInput ->
          NativeInput(configuration).use { configurationInput ->
            callWithError { error ->
              kestara_runtime_submit(
                runtime.toULong(),
                operation.toULong(),
                command,
                target.toULong(),
                timeout.inWholeMilliseconds,
                textInput.pointer,
                textInput.length,
                secondaryInput.pointer,
                secondaryInput.length,
                number,
                secondaryNumber,
                dataInput.pointer,
                dataInput.length,
                configurationInput.pointer,
                configurationInput.length,
                error,
              )
            }
          }
        }
      }
    }
  }

  private fun outputString(
    operation: (CPointer<kestara_bytes>, CPointer<kestara_bytes>) -> Int,
  ): String = memScoped {
    val output = emptyOutput()
    val error = emptyOutput()
    checkStatus(operation(output.ptr, error.ptr), error)
    takeOwnedBytes(output).decodeToString()
  }

  private fun callWithError(operation: (CPointer<kestara_bytes>) -> Int) {
    memScoped {
      val error = emptyOutput()
      checkStatus(operation(error.ptr), error)
    }
  }

  private fun kotlinx.cinterop.MemScope.emptyOutput(): kestara_bytes = alloc<kestara_bytes> {
    data = null
    length = 0.convert()
  }

  private fun checkStatus(status: Int, error: kestara_bytes) {
    if (status == 0) return
    val message = takeOwnedBytes(error).decodeToString().ifBlank { "Native WebRTC operation failed" }
    throw WebRtcException(message)
  }

  private fun takeOwnedBytes(value: kestara_bytes): ByteArray {
    val copy = value.data?.readBytes(value.length.toInt()) ?: ByteArray(0)
    kestara_bytes_free(value.readValue())
    value.data = null
    value.length = 0.convert()
    return copy
  }

  private fun ownedBytes(value: kotlinx.cinterop.CValue<kestara_bytes>): ByteArray =
    value.useContents { takeOwnedBytes(this) }

  private const val CREATE_PEER = 1
  private const val RESTART_ICE = 2
  private const val SET_CONFIGURATION = 3
  private const val CREATE_DESCRIPTION = 4
  private const val SET_LOCAL_DESCRIPTION = 5
  private const val SET_REMOTE_DESCRIPTION = 6
  private const val ADD_ICE_CANDIDATE = 7
  private const val CREATE_DATA_CHANNEL = 8
  private const val SEND_TEXT = 9
  private const val SEND_BINARY = 10
  private const val TRY_SEND_TEXT = 11
  private const val TRY_SEND_BINARY = 12
  private const val DATA_CHANNEL_WRITABLE = 13
  private const val DATA_CHANNEL_OUTSTANDING_BYTES = 14
  private const val SET_DATA_CHANNEL_THRESHOLDS = 15
  private const val GET_STATS = 16
  private const val ROTATE_CERTIFICATE = 17
  private const val CLOSE_DATA_CHANNEL = 18
  private const val CLOSE_PEER = 19
  private const val CLOSE_RUNTIME = 20
}

private class NativeInput(
  value: ByteArray?,
  nullable: Boolean = false,
) : AutoCloseable {
  private val actualLength: Int = value?.size ?: 0
  private val bytes: ByteArray? = when {
    value == null && nullable -> null
    value == null || value.isEmpty() -> byteArrayOf(0)
    else -> value
  }
  private val pinned = bytes?.pin()
  val pointer: CPointer<UByteVar>?
    get() = pinned?.addressOf(0)?.reinterpret<ByteVar>()?.reinterpret()
  val length: ULong
    get() = actualLength.toULong()

  override fun close() {
    pinned?.unpin()
  }
}
