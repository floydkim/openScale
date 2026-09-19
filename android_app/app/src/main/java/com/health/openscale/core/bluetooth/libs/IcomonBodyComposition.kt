/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.libs

import com.health.openscale.core.data.GenderType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * ICOMON BF-L303B body composition model.
 *
 * The scale reports weight, heart rate and impedance. The remaining values are
 * calculated locally from the user's profile and the reported impedance.
 */
object IcomonBodyComposition {
    data class Result(
        val bmi: Double,
        val bodyFatPercent: Double,
        val fatFreeMassKg: Double,
        val waterPercent: Double,
        val waterMassKg: Double,
        val boneMassKg: Double,
        val musclePercent: Double,
        val skeletalMusclePercent: Double,
        val proteinPercent: Double,
        val visceralFatIndex: Double,
        val subcutaneousFatPercent: Double,
        val basalMetabolicRate: Int,
    )

    private val bodyFatRows = rows(
        female = doubleArrayOf(-3332.0, 7509.0, 196.0, 72.0, 227193.0),
        male = doubleArrayOf(-3315.0, 6216.0, 183.0, 85.0, 225540.0),
    )
    private val muscleRows = rows(
        female = doubleArrayOf(31860.0, 19340.0, -2060.0, -1320.0, -1645560.0),
        male = doubleArrayOf(28670.0, 38940.0, -4080.0, -1235.0, -1576650.0),
    )
    private val waterRows = rows(
        female = doubleArrayOf(87700.0, 297300.0, 12800.0, -6030.0, 517500.0),
        male = doubleArrayOf(93900.0, 375800.0, -3200.0, -6925.0, 97000.0),
    )
    private val bmrRows = rows(
        female = doubleArrayOf(75432.0, 99474.0, -34382.0, -3090.0, -2882821.0),
        male = doubleArrayOf(75037.0, 131523.0, -43376.0, -3486.0, -3117751.0),
    )
    private val visceralFatRows = rows(
        female = doubleArrayOf(-1651.0, 2628.0, 649.0, 24.0, 123445.0),
        male = doubleArrayOf(-2675.0, 4200.0, 1462.0, 123.0, 139871.0),
    )

    fun calculate(
        gender: GenderType,
        ageYears: Int,
        heightCm: Double,
        weightKg: Double,
        impedanceOhm: Double,
    ): Result {
        require(ageYears >= 0 && heightCm > 0 && weightKg > 0 && impedanceOhm > 0) {
            "프로필과 측정값은 양수여야 합니다."
        }

        val rawBodyFatPercent = clamp(
            regression(row(bodyFatRows, gender), heightCm, weightKg, ageYears, impedanceOhm) / weightKg * 100,
            5.0,
            45.0,
        )
        val bodyFatPercent = vendorRound(rawBodyFatPercent)
        val fatMassKg = weightKg * rawBodyFatPercent / 100
        val fatFreeMassKg = weightKg - fatMassKg

        val muscleRegression = regression(row(muscleRows, gender), heightCm, weightKg, ageYears, impedanceOhm) / 10
        val fatFreeMassResidual = fatFreeMassKg - muscleRegression
        val muscleMassKg = when {
            fatFreeMassResidual >= 4 -> muscleRegression + fatFreeMassResidual - 4
            fatFreeMassResidual > 1 -> muscleRegression
            else -> muscleRegression + fatFreeMassResidual - 1
        }
        val boneMassKg = vendorRound(clamp(fatFreeMassKg - muscleMassKg, 1.0, 4.0))
        val musclePercent = vendorRound(muscleMassKg / weightKg * 100)

        val rawWaterPercent = regression(row(waterRows, gender), heightCm, weightKg, ageYears, impedanceOhm) / weightKg
        val waterResidual = muscleMassKg / weightKg * 100 - rawWaterPercent
        val correctedWaterPercent = when {
            waterResidual >= 32 -> muscleMassKg / weightKg * 100 - 32
            waterResidual > 5 -> rawWaterPercent
            else -> muscleMassKg / weightKg * 100 - 5
        }
        val waterPercent = vendorRound(clamp(correctedWaterPercent, 20.0, 85.0))
        val proteinPercent = vendorRound(
            clamp(muscleMassKg / weightKg * 100 - clamp(rawWaterPercent, 20.0, 85.0), 5.0, 32.0),
        )

        val rawVisceralFat = regression(row(visceralFatRows, gender), heightCm, weightKg, ageYears, impedanceOhm) * 10
        val truncatedVisceralFat = rawVisceralFat.toInt()
        val visceralBase = truncatedVisceralFat / 10 * 10
        val visceralRounded = if (truncatedVisceralFat % 10 < 6) visceralBase else visceralBase + 5
        val visceralFatIndex = vendorRound(clamp(visceralRounded / 10.0, 1.0, 59.0))

        val basalMetabolicRate = roundPositive(
            clamp(regression(row(bmrRows, gender), heightCm, weightKg, ageYears, impedanceOhm), 400.0, 3500.0),
        )
        val subcutaneousFatPercent = vendorRound(bodyFatPercent * (-0.0002 * bodyFatPercent + 0.72))
        val waterMassKg = weightKg * waterPercent / 100

        return Result(
            bmi = vendorRound(clamp(weightKg * 10000 / (heightCm * heightCm), 4.0, 185.5)),
            bodyFatPercent = bodyFatPercent,
            fatFreeMassKg = vendorRound(fatFreeMassKg),
            waterPercent = waterPercent,
            waterMassKg = vendorRound(waterMassKg),
            boneMassKg = boneMassKg,
            musclePercent = musclePercent,
            skeletalMusclePercent = skeletalMusclePercent(
                heightCm = heightCm,
                weightKg = weightKg,
                ageYears = ageYears,
                impedanceOhm = impedanceOhm,
                gender = gender,
                muscleMassKg = muscleMassKg,
            ),
            proteinPercent = proteinPercent,
            visceralFatIndex = visceralFatIndex,
            subcutaneousFatPercent = subcutaneousFatPercent,
            basalMetabolicRate = basalMetabolicRate,
        )
    }

    private fun skeletalMusclePercent(
        heightCm: Double,
        weightKg: Double,
        ageYears: Int,
        impedanceOhm: Double,
        gender: GenderType,
        muscleMassKg: Double,
    ): Double {
        val sexFlag = if (gender == GenderType.MALE) 1 else 0
        var raw = impedanceOhm * -0.017 +
            weightKg * 0.1745 +
            heightCm * 0.2573 +
            sexFlag * 2.4269 -
            ageYears * 0.0161 -
            20.2165
        val ratio = raw / muscleMassKg
        if (ratio >= 0.7) raw = muscleMassKg * 0.7
        else if (ratio <= 0.45) raw = muscleMassKg * 0.45
        return vendorRound(raw / weightKg * 100)
    }

    private fun regression(
        row: DoubleArray,
        heightCm: Double,
        weightKg: Double,
        ageYears: Int,
        impedanceOhm: Double,
    ): Double = (
        heightCm * row[0] +
            weightKg * row[1] +
            ageYears * row[2] +
            impedanceOhm * row[3] +
            row[4]
        ) / 10000

    private fun vendorRound(value: Double): Double {
        val integer = value.toInt()
        val tenths = (value - integer) * 10
        val roundedTenths = if (tenths % 1 > 0.5) ceil(tenths) else floor(tenths)
        return integer + roundedTenths / 10
    }

    private fun roundPositive(value: Double): Int = floor(value + 0.5).toInt()

    private fun clamp(value: Double, minimum: Double, maximum: Double): Double = value.coerceIn(minimum, maximum)

    private fun rows(female: DoubleArray, male: DoubleArray): Map<GenderType, DoubleArray> =
        mapOf(GenderType.FEMALE to female, GenderType.MALE to male)

    private fun row(rows: Map<GenderType, DoubleArray>, gender: GenderType): DoubleArray = rows.getValue(gender)
}
