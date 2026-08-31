// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Sensors

/** Reports the head unit NIGHT sensor so Android Auto map apps switch theme live. */
class NightModeEvent(isNight: Boolean) :
    AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(isNight)) {

    companion object {
        private fun makeProto(isNight: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addNightMode(
                    Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(isNight)
                )
                .build()
    }
}
