package com.enderdash.kestara.webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ConfigurationTest {
  @Test
  fun buildsImmutablePeerConfiguration() {
    val server = IceServer.authenticated(
      "kestara-user",
      "kestara-secret",
      "turn:turn.example.com:3478",
    )
    val configuration = PeerConnectionConfiguration(
      iceServers = listOf(server),
      minPort = 10_000,
      maxPort = 10_010,
      operationTimeout = 3.seconds,
      iceOptions = IceOptions(networkTypes = setOf(IceNetworkType.UDP4, IceNetworkType.TCP4)),
    )

    assertEquals(listOf(server), configuration.iceServers)
    assertEquals(3.seconds, configuration.operationTimeout)
    assertEquals(setOf(IceNetworkType.UDP4, IceNetworkType.TCP4), configuration.iceOptions.networkTypes)
  }

  @Test
  fun rejectsConflictingReliabilityLimits() {
    assertFailsWith<IllegalArgumentException> {
      DataChannelOptions(maxPacketLifeTime = 100, maxRetransmits = 2)
    }
  }

  @Test
  fun rejectsInvalidTransportLimits() {
    assertFailsWith<IllegalArgumentException> { IceOptions(networkTypes = emptySet()) }
    assertFailsWith<IllegalArgumentException> { IceOptions(candidatePoolSize = 2) }
    assertFailsWith<IllegalArgumentException> {
      SctpOptions(sendBufferLimit = 1_024, receiveBufferSize = 32_768, maximumMessageSize = 65_536)
    }
  }
}
