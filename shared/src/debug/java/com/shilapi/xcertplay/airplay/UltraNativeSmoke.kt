// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.airplay

import java.io.File

/** Explicit development entry point. No Activity, socket, vehicle service or display is opened. */
object UltraNativeSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val scene = runResearch(args)
        println("{\"jni\":true,\"persistentVm\":true,\"resetCompleted\":true," +
            "\"requestLayout\":true,\"sceneBytes\":${scene.size},\"simulatedLifecycle\":true," +
            "\"phoneConnected\":false,\"physicalDisplay\":false,\"fullUltraUI\":false}")
    }

    /** Returns a detached, research-labeled scene; never a live/presented frame. */
    fun runResearch(args: Array<String>): ByteArray {
        require(args.size == 4) { "LIBRARY RESOURCE_DIRECTORY BRIDGE_SOURCE ENTRY_SOURCE required" }
        fun source(path: String): ByteArray = File(path).let {
            require(it.length() in 1..4 * 1024 * 1024) { "Source size limit" }
            it.readBytes()
        }
        return UltraNativeRuntime.open(File(args[1]), source(args[2]), source(args[3]),
            researchOverride = true, loadLibrary = { System.load(args[0]) }).use { runtime ->
            fun inner(bytes: ByteArray): Map<String, Any?> =
                BoundedPlist.decode(((Opack.decode(bytes) as Opack.Dict)["data"] as Opack.Data).bytes())!!
            UiSyncPanelRouter(setOf("center")) { UltraScriptPanelSession(runtime) }.use { router ->
                val reset = inner(router.start("center").single())
                check(reset["type"] == 0L)
                val response = BplistCodec.encode(mapOf("type" to 1L, "ssn" to reset.getValue("ssn"),
                    "psn" to 1L, "asn" to reset.getValue("psn"), "version" to 2L, "id" to "fixture-peer"))
                val messages = mutableListOf<Map<String, Any?>>()
                messages += router.receive(Opack.encode(Opack.dict("displayUUID" to Opack.Text("center"),
                    "data" to Opack.Data(response)))).map(::inner)
                repeat(8) { messages += router.poll().map(::inner) }
                check(messages.any { it["type"] == 1L })
                runtime.request(5, byteArrayOf(1)); runtime.request(4, byteArrayOf(4))
                val commands = mutableListOf<Map<String, Any?>>()
                repeat(10) {
                    commands += router.poll().map(::inner).filter { it["type"] == 2L }
                        .mapNotNull { (it["data"] as? ByteArray)?.let(BoundedPlist::decode) }
                }
                check(commands.any { it["type"] == 5L })
                runtime.request(3) as ByteArray
            }
        }
    }
}
