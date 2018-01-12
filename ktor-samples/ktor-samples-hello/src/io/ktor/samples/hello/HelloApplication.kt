package io.ktor.samples.hello

import io.ktor.application.*
import io.ktor.content.*
import io.ktor.features.*
import io.ktor.response.*
import io.ktor.routing.*
import java.io.*

fun Application.main() {
//    val largeFile = File("/home/cy/Downloads/ideaIU-181.1818.tar.gz")
    val largeFile = File("/home/cy/VirtualBox VMs/vagrant-development_default_1456926071043_87296/box-disk1.vmdk")
    val smallFile = File("/home/cy/projects/ktor/ktor-samples/ktor-samples-hello/src/io/ktor/samples/hello/HelloApplication.kt")

    val largeFileContent = LocalFileContent(largeFile)
    val smallFileContent = LocalFileContent(smallFile)

    install(DefaultHeaders)
//    install(CallLogging)
    install(Routing) {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/large") {
            call.respond(largeFileContent)
        }
        get("/small") {
            call.respond(smallFileContent)
        }
    }
}
