// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): aap/protocol/messages/DrivingStatusEvent.kt
package io.motohub.android.aa

import com.google.protobuf.Message
import io.motohub.android.aa.proto.Sensors

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message =
            Sensors.SensorBatch.newBuilder()
                .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder().setStatus(status.number))
                .build()
    }
}
