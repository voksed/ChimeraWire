package org.amnezia.awg

object GoBackend {
    external fun awgVersion(): String
    external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int
    external fun awgTurnOff(handle: Int)
    external fun awgGetConfig(handle: Int): String?
    external fun awgGetSocketV4(handle: Int): Int
    external fun awgGetSocketV6(handle: Int): Int

    init { System.loadLibrary("amneziawg") }
}
