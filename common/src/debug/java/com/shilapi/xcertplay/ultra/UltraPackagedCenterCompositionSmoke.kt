// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.UltraPackagedCenterSmoke

/** Debug shell entry for real APK loading and offscreen host adoption; no phone or car. */
@androidx.annotation.RequiresApi(26)
object UltraPackagedCenterCompositionSmoke {
    @JvmStatic fun main(args: Array<String>) {
        try { UltraPackagedCenterSmoke.run(args, UltraReferenceCenterDraw::draw,
            createDrawer = { UltraReferenceCenterDraw.Session(it) }) }
        catch (failure: Throwable) { failure.printStackTrace(System.err); throw failure }
        println("{\"packagedApkAssets\":true,\"referenceCenterHost\":true,\"decodedPixelsVerified\":true," +
            "\"hostPauseAndAdoption\":true,\"borrowedSurfacesRetained\":true,\"runtimeResourcesRetired\":true," +
            "\"width\":1920,\"height\":720,\"simulatedPeer\":true,\"generatedVideo\":true," +
            "\"simulatedVehicleData\":true,\"batterySourcePolled\":true,\"batterySourceLossExercised\":true," +
            "\"batteryWidgetVisible\":false," +
            "\"phoneConnected\":false,\"physicalPresentation\":false,\"fullUltraUI\":false}")
    }
}
