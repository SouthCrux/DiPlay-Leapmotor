package com.southcrux.c11diagnostics

import android.car.Car
import android.content.Context
import android.os.Handler

/** Compiled against the official optional SDK library; no OEM JARs or hidden-API reflection. */
internal object PublicCarApi : DiagnosticCarFactory {
    override fun create(
        context: Context,
        handler: Handler,
        lifecycle: (DiagnosticCarSession, Boolean) -> Unit,
    ): DiagnosticCarSession? {
        val session = PublicCarSession()
        val car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
            session.car = connected
            lifecycle(session, ready)
        } ?: return null
        session.car = car
        return session
    }

    private class PublicCarSession : DiagnosticCarSession {
        lateinit var car: Car

        override fun propertyManager(): Any = car.getCarManager(Car.PROPERTY_SERVICE)
            ?: error("No property manager")

        override fun disconnect() = car.disconnect()
    }
}
