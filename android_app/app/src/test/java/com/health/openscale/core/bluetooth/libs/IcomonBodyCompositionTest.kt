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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.GenderType
import org.junit.Test

class IcomonBodyCompositionTest {

    @Test
    fun `matches known ICOMON female capture`() {
        val composition = IcomonBodyComposition.calculate(
            gender = GenderType.FEMALE,
            ageYears = 31,
            heightCm = 163.0,
            weightKg = 63.75,
            impedanceOhm = 582.0,
        )

        assertThat(composition.bmi).isWithin(0.0001).of(24.0)
        assertThat(composition.bodyFatPercent).isWithin(0.0001).of(33.1)
        assertThat(composition.waterPercent).isWithin(0.0001).of(48.1)
        assertThat(composition.boneMassKg).isWithin(0.0001).of(3.2)
    }
}
