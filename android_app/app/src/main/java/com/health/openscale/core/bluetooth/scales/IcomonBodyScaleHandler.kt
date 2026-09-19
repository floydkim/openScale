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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.IcomonBodyComposition
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * ICOMON BF-L303B body scale (`Body scale`, model `FI2019LB-B`).
 *
 * The device uses the shared 0xFFB0 service but a different 20-byte protocol
 * from [MGBHandler]. FFB2 carries live A2 weights and FFB3 carries the final
 * A3 result as an indication.
 *
 * The protocol implementation is based on observed device behavior for
 * interoperability and contains no vendor application code or assets.
 */
class IcomonBodyScaleHandler : ScaleDeviceHandler() {

    private val SERVICE = uuid16(0xFFB0)
    private val CHAR_WRITE = uuid16(0xFFB1) // write
    private val CHAR_LIVE = uuid16(0xFFB2) // notify
    private val CHAR_RESULT = uuid16(0xFFB3) // indicate

    private var stableWeightKg: Double? = null
    private var finalPublished = false
    private var outgoingSequence = 1

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        // The scale can omit 0xFFB0 from its advertisement; the exact name is
        // specific enough to identify it before GATT service discovery.
        if (!device.name.equals("Body scale", ignoreCase = true)) return null

        val capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.USER_SYNC,
        )
        return DeviceSupport(
            displayName = "ICOMON BF-L303B",
            capabilities = capabilities,
            implemented = setOf(
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.USER_SYNC,
            ),
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        stableWeightKg = null
        finalPublished = false
        outgoingSequence = 1

        setNotifyOn(SERVICE, CHAR_LIVE)
        setNotifyOn(SERVICE, CHAR_RESULT)

        val heightCm = user.bodyHeight.toInt().takeIf { it in 100..250 }
        if (heightCm != null) {
            writeTo(
                SERVICE,
                CHAR_WRITE,
                buildProfileFrame(heightCm, user.gender, Instant.now().epochSecond.toInt(), outgoingSequence++),
                withResponse = true,
            )
        } else {
            logW("Skipping profile sync: invalid height ${user.bodyHeight}")
        }
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_LIVE && characteristic != CHAR_RESULT) return

        val frame = parseFrame(data) ?: return
        val type = frame.payload.firstOrNull()?.toInt()?.and(0xFF) ?: return
        if (type == TYPE_INCOMING_ACK || type == TYPE_DEVICE_INFO || type == TYPE_FINAL_RESULT) {
            sendAcknowledgement(frame.sequence)
        }

        when (type) {
            TYPE_LIVE_WEIGHT -> decodeWeight(frame)?.let(::handleLiveWeight)
            TYPE_FINAL_RESULT -> decodeResult(frame)?.let { publishResult(it, user) }
        }
    }

    override fun onDisconnected() {
        if (!finalPublished) {
            stableWeightKg?.let { weight ->
                publish(ScaleMeasurement().apply {
                    dateTime = Date()
                    this[MeasurementType.WEIGHT] = Kg(weight.toFloat())
                })
                finalPublished = true
            }
        }
        stableWeightKg = null
    }

    private fun handleLiveWeight(weight: WeightFrame) {
        if (weight.weightKg <= 0.0) return
        if (weight.state == STATE_STABLE) {
            stableWeightKg = weight.weightKg
        } else {
            userInfo(R.string.bluetooth_scale_info_measuring_weight, weight.weightKg.toFloat())
        }
    }

    private fun publishResult(result: ResultFrame, user: ScaleUser) {
        if (finalPublished) return

        val measurement = ScaleMeasurement().apply {
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(result.weightKg.toFloat())
            if (result.heartRateBpm > 0) this[MeasurementType.HEART_RATE] = Bpm(result.heartRateBpm)
            if (result.impedanceOhm > 0) this[MeasurementType.IMPEDANCE] = Ohm(result.impedanceOhm.toFloat())

            if (result.impedanceOhm in 1..1500 && user.bodyHeight > 0f) {
                val composition = IcomonBodyComposition.calculate(
                    gender = user.gender,
                    ageYears = user.age,
                    heightCm = user.bodyHeight.toDouble(),
                    weightKg = result.weightKg,
                    impedanceOhm = result.impedanceOhm.toDouble(),
                )
                this[MeasurementType.BODY_FAT] = Percent(composition.bodyFatPercent.toFloat())
                this[MeasurementType.WATER] = Percent(composition.waterPercent.toFloat())
                this[MeasurementType.MUSCLE] = Percent(composition.musclePercent.toFloat())
                this[MeasurementType.BONE] = Kg(composition.boneMassKg.toFloat())
                this[MeasurementType.LBM] = Kg(composition.fatFreeMassKg.toFloat())
                this[MeasurementType.VISCERAL_FAT] = composition.visceralFatIndex.toFloat()
                this[MeasurementType.PROTEIN] = Percent(composition.proteinPercent.toFloat())
                this[MeasurementType.BMR] = Kcal(composition.basalMetabolicRate.toFloat())
            }
        }

        publish(measurement)
        finalPublished = true
        stableWeightKg = null
    }

    private fun sendAcknowledgement(acknowledgedSequence: Int) {
        writeTo(
            SERVICE,
            CHAR_WRITE,
            buildAcknowledgement(outgoingSequence++, acknowledgedSequence),
            withResponse = true,
        )
    }

    companion object {
        private const val FRAME_LENGTH = 20
        private const val MAX_PAYLOAD_LENGTH = 16
        private const val TYPE_DEVICE_INFO = 0xA1
        private const val TYPE_INCOMING_ACK = 0xA0
        private const val TYPE_LIVE_WEIGHT = 0xA2
        private const val TYPE_FINAL_RESULT = 0xA3
        private const val TYPE_ACK_OUT = 0xB0
        private const val STATE_STABLE = 0x03

        internal data class Frame(
            val sequence: Int,
            val payload: ByteArray,
        )

        internal data class WeightFrame(
            val state: Int,
            val weightKg: Double,
        )

        internal data class ResultFrame(
            val weightKg: Double,
            val heartRateBpm: Int,
            val impedanceOhm: Int,
        )

        internal fun buildFrame(sequence: Int, payload: ByteArray): ByteArray {
            require(payload.size <= MAX_PAYLOAD_LENGTH)
            val frame = ByteArray(FRAME_LENGTH)
            frame[0] = sequence.toByte()
            frame[1] = payload.size.toByte()
            payload.copyInto(frame, destinationOffset = 3)
            frame[FRAME_LENGTH - 1] = checksum(frame).toByte()
            return frame
        }

        internal fun parseFrame(data: ByteArray): Frame? {
            if (data.size != FRAME_LENGTH) return null
            val payloadLength = data[1].toInt() and 0xFF
            if (unsigned(data[2]) != 0 || payloadLength > MAX_PAYLOAD_LENGTH) return null
            if ((data.last().toInt() and 0xFF) != checksum(data)) return null
            return Frame(
                sequence = data[0].toInt() and 0xFF,
                payload = data.copyOfRange(3, 3 + payloadLength),
            )
        }

        internal fun decodeWeight(frame: Frame): WeightFrame? {
            if (frame.payload.size < 6 || unsigned(frame.payload[0]) != TYPE_LIVE_WEIGHT) return null
            return WeightFrame(
                state = unsigned(frame.payload[1]),
                weightKg = u24be(frame.payload, 3) / 1000.0,
            )
        }

        internal fun decodeResult(frame: Frame): ResultFrame? {
            if (frame.payload.size < 8 || unsigned(frame.payload[0]) != TYPE_FINAL_RESULT) return null
            return ResultFrame(
                weightKg = u24be(frame.payload, 2) / 1000.0,
                heartRateBpm = unsigned(frame.payload[5]),
                impedanceOhm = u16be(frame.payload, 6),
            )
        }

        internal fun buildAcknowledgement(sequence: Int, acknowledgedSequence: Int): ByteArray =
            buildFrame(sequence, byteArrayOf(TYPE_ACK_OUT.toByte(), acknowledgedSequence.toByte(), 0))

        internal fun buildProfileFrame(
            heightCm: Int,
            gender: GenderType,
            timestamp: Int,
            sequence: Int,
        ): ByteArray {
            require(heightCm in 100..250)
            return buildFrame(
                sequence,
                byteArrayOf(
                    0xB1.toByte(),
                    (timestamp ushr 24).toByte(),
                    (timestamp ushr 16).toByte(),
                    (timestamp ushr 8).toByte(),
                    timestamp.toByte(),
                    0x02,
                    0x1C,
                    0x01,
                    heightCm.toByte(),
                    0x00,
                    0x00,
                    if (gender.isMale()) 0x9F.toByte() else 0x1F,
                    0x0F,
                    0x13,
                    0x88.toByte(),
                    0x01,
                ),
            )
        }

        private fun checksum(frame: ByteArray): Int {
            var sum = 0
            for (index in 3 until FRAME_LENGTH - 1) sum += unsigned(frame[index])
            return sum and 0x1F
        }

        private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

        private fun u16be(data: ByteArray, offset: Int): Int =
            (unsigned(data[offset]) shl 8) or unsigned(data[offset + 1])

        private fun u24be(data: ByteArray, offset: Int): Int =
            (unsigned(data[offset]) shl 16) or
                (unsigned(data[offset + 1]) shl 8) or
                unsigned(data[offset + 2])
    }
}
