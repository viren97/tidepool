package dev.tidepool.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TidepoolServerApplication

fun main(args: Array<String>) {
    runApplication<TidepoolServerApplication>(*args)
}
