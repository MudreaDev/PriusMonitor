package org.betsy.monitor

import org.betsy.transport.ElmTransport

interface ConnectableTransport : ElmTransport {
    fun connect()
}
