/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.device
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.ScaleCatalog.uuid16
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Test

/**
 * Unit tests for [IcomonBodyScaleHandler]'s 20-byte frame parser and GATT lifecycle.
 * Fixtures are synthetic and preserve the observed protocol layout without personal captures.
 */
class IcomonBodyScaleHandlerTest {

    private val service = uuid16(0xFFB0)
    private val write = uuid16(0xFFB1)
    private val live = uuid16(0xFFB2)
    private val result = uuid16(0xFFB3)

    // Synthetic frames based on the observed [sequence][length][00][payload][checksum] layout.
    // The checksum is sum(bytes[3..18]) & 0x1F.
    private val liveFrame = hex("24 06 00 A2 03 00 01 26 EC 00 00 00 00 00 00 00 00 00 00 18")
    private val unstableLiveFrame = hex("25 06 00 A2 02 00 01 26 EC 00 00 00 00 00 00 00 00 00 00 17")
    private val finalFrame = hex("07 08 00 A3 00 01 26 EC 48 01 C2 00 00 00 00 00 00 00 00 01")

    @Test
    fun `decodes synthetic live weight frame`() {
        val decoded = IcomonBodyScaleHandler.parseFrame(liveFrame)?.let(IcomonBodyScaleHandler::decodeWeight)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.state).isEqualTo(0x03)
        assertThat(decoded.weightKg).isWithin(0.0001).of(75.5)
    }

    @Test
    fun `decodes synthetic final measurement frame`() {
        val decoded = IcomonBodyScaleHandler.parseFrame(finalFrame)?.let(IcomonBodyScaleHandler::decodeResult)

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.weightKg).isWithin(0.0001).of(75.5)
        assertThat(decoded.heartRateBpm).isEqualTo(72)
        assertThat(decoded.impedanceOhm).isEqualTo(450)
    }

    @Test
    fun `rejects malformed frames`() {
        val badChecksum = liveFrame.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() + 1).toByte()
        }
        val badFragment = liveFrame.copyOf().apply { this[2] = 1 }
        val badPayloadLength = liveFrame.copyOf().apply { this[1] = 17 }

        assertThat(IcomonBodyScaleHandler.parseFrame(badChecksum)).isNull()
        assertThat(IcomonBodyScaleHandler.parseFrame(badFragment)).isNull()
        assertThat(IcomonBodyScaleHandler.parseFrame(liveFrame.copyOf(19))).isNull()
        assertThat(IcomonBodyScaleHandler.parseFrame(badPayloadLength)).isNull()
    }

    @Test
    fun `builds acknowledgement with incoming sequence`() {
        val frame = IcomonBodyScaleHandler.buildAcknowledgement(sequence = 9, acknowledgedSequence = 0x7A)
        val parsed = IcomonBodyScaleHandler.parseFrame(frame)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.sequence).isEqualTo(9)
        assertThat(parsed.payload.unsignedValues()).containsExactly(0xB0, 0x7A, 0).inOrder()
        assertThat(frame.last().toInt() and 0xFF).isEqualTo(0x0A)
    }

    @Test
    fun `builds male and female profile frames`() {
        val male = IcomonBodyScaleHandler.buildProfileFrame(175, GenderType.MALE, 0x01020304, 1)
        val female = IcomonBodyScaleHandler.buildProfileFrame(163, GenderType.FEMALE, 0x01020304, 1)
        val malePayload = IcomonBodyScaleHandler.parseFrame(male)!!.payload.unsignedValues()
        val femalePayload = IcomonBodyScaleHandler.parseFrame(female)!!.payload.unsignedValues()

        assertThat(malePayload).containsExactly(
            0xB1, 0x01, 0x02, 0x03, 0x04, 0x02, 0x1C, 0x01, 175, 0, 0, 0x9F, 0x0F, 0x13, 0x88, 1,
        ).inOrder()
        assertThat(femalePayload[8]).isEqualTo(163)
        assertThat(femalePayload[11]).isEqualTo(0x1F)
    }

    @Test
    fun `acknowledges device info and incoming acknowledgement frames`() {
        val transport = CapturingTransport()
        val user = syntheticUser()
        val handler = IcomonBodyScaleHandler()
        handler.attach(transport, CapturingCallbacks(), EmptySettings(), FixedDataProvider(user), CoroutineScope(EmptyCoroutineContext))
        handler.handleConnected(user)
        transport.writes.clear()

        handler.handleNotification(
            result,
            IcomonBodyScaleHandler.buildFrame(0x10, byteArrayOf(0xA0.toByte())),
        )
        handler.handleNotification(
            result,
            IcomonBodyScaleHandler.buildFrame(0x11, byteArrayOf(0xA1.toByte())),
        )

        assertThat(transport.writes.map { IcomonBodyScaleHandler.parseFrame(it.payload)!!.payload.unsignedValues() })
            .containsExactly(listOf(0xB0, 0x10, 0), listOf(0xB0, 0x11, 0)).inOrder()
    }

    @Test
    fun `connects both data characteristics and publishes final measurement`() {
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        val user = syntheticUser()
        val handler = IcomonBodyScaleHandler()
        handler.attach(transport, callbacks, EmptySettings(), FixedDataProvider(user), CoroutineScope(EmptyCoroutineContext))

        handler.handleConnected(user)
        assertThat(transport.notifications).containsExactly(service to live, service to result).inOrder()
        assertThat(transport.writes).hasSize(1)
        assertThat(transport.writes.single().characteristic).isEqualTo(write)

        transport.writes.clear()
        handler.handleNotification(result, finalFrame)

        assertThat(callbacks.published).hasSize(1)
        val measurement = callbacks.published.single()
        assertThat(measurement[MeasurementType.WEIGHT]!!.value).isWithin(0.0001f).of(75.5f)
        assertThat(measurement[MeasurementType.HEART_RATE]!!.value).isEqualTo(72)
        assertThat(measurement[MeasurementType.IMPEDANCE]!!.value).isEqualTo(450f)
        assertThat(measurement[MeasurementType.BODY_FAT]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.WATER]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.MUSCLE]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.BONE]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.VISCERAL_FAT]!!).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.PROTEIN]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.BMR]!!.value).isGreaterThan(0f)
        assertThat(measurement[MeasurementType.LBM]!!.value).isGreaterThan(0f)
        assertThat(measurement.values.keys).containsAtLeast(
            MeasurementType.WEIGHT,
            MeasurementType.HEART_RATE,
            MeasurementType.IMPEDANCE,
            MeasurementType.BODY_FAT,
            MeasurementType.WATER,
            MeasurementType.MUSCLE,
            MeasurementType.BONE,
            MeasurementType.VISCERAL_FAT,
            MeasurementType.PROTEIN,
            MeasurementType.BMR,
            MeasurementType.LBM,
        )
        assertThat(transport.writes).hasSize(1)
        assertThat(IcomonBodyScaleHandler.parseFrame(transport.writes.single().payload)!!.payload.unsignedValues())
            .containsExactly(0xB0, 7, 0).inOrder()

        handler.handleNotification(live, liveFrame)
        handler.handleNotification(result, finalFrame)
        assertThat(callbacks.published).hasSize(1)
        assertThat(transport.writes).hasSize(2)
    }

    @Test
    fun `publishes stable live weight if final result never arrives`() {
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        val user = syntheticUser()
        val handler = IcomonBodyScaleHandler()
        handler.attach(transport, callbacks, EmptySettings(), FixedDataProvider(user), CoroutineScope(EmptyCoroutineContext))

        handler.handleConnected(user)
        handler.handleNotification(live, unstableLiveFrame)
        assertThat(callbacks.published).isEmpty()
        handler.handleNotification(live, liveFrame)
        handler.handleDisconnected()
        handler.handleDisconnected()

        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single().values).containsKey(MeasurementType.WEIGHT)
        assertThat(callbacks.published.single().values).doesNotContainKey(MeasurementType.IMPEDANCE)
    }

    @Test
    fun `matches Body scale with or without the shared service`() {
        val handler = IcomonBodyScaleHandler()
        val supported = device("Body scale", service)

        val support = handler.supportFor(supported)!!
        assertThat(support.displayName).isEqualTo("ICOMON BF-L303B")
        assertThat(support.capabilities).containsExactly(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
        )
        assertThat(support.implemented).containsExactly(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.USER_SYNC,
        )
        assertThat(handler.supportFor(device("BODY SCALE", service))).isNotNull()
        assertThat(handler.supportFor(device("Body scale"))).isNotNull()
        assertThat(handler.supportFor(device("Body scale Pro", service))).isNull()
        assertThat(handler.supportFor(device("ICOMON", service))).isNull()
        assertThat(handler.supportFor(device("swan", service))).isNull()
        assertThat(handler.supportFor(device("yg", service))).isNull()
    }

    private fun syntheticUser() = ScaleUser(
        id = 1,
        birthday = Calendar.getInstance().apply {
            clear()
            set(1982, Calendar.JUNE, 15)
        }.time,
        bodyHeight = 182f,
        gender = GenderType.MALE,
    )

    private fun ByteArray.unsignedValues(): List<Int> = map { it.toInt() and 0xFF }

    private data class Write(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean,
    )

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val notifications = mutableListOf<Pair<UUID, UUID>>()
        val writes = mutableListOf<Write>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += Write(service, characteristic, payload.copyOf(), withResponse)
        }

        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }

        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class EmptySettings : ScaleDeviceHandler.DriverSettings {
        override fun getInt(key: String, default: Int) = default
        override fun putInt(key: String, value: Int) = Unit
        override fun getString(key: String, default: String?) = default
        override fun putString(key: String, value: String) = Unit
        override fun remove(key: String) = Unit
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser() = user
        override fun usersForDevice() = listOf(user)
        override fun lastMeasurementFor(userId: Int) = null
    }
}
