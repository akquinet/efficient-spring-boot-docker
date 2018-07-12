package com.example.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

class DemoApplicationIT {

    @get:Rule
    var appContainer = KGenericContainer("spring-docker-demo:latest")
            .waitingFor(Wait.forListeningPort())
            .withExposedPorts(8080)
            .withFileSystemBind("./target/jacoco-agent", "/jacoco-agent")
            .withFileSystemBind("./target/jacoco-report", "/jacoco-report")
            .withCommand("/usr/bin/java",
                    "-javaagent:/jacoco-agent/org.jacoco.agent-runtime.jar=destfile=/jacoco-report/jacoco-it.exec",
                    "-jar",
                    "/app.jar"
            )


    val client: RestTemplate = RestTemplateBuilder().build()

    @Test
    fun `can call ping`() {
        val url = "http://localhost:${appContainer.getMappedPort(8080)}/ping"

        val response = client.getForEntity(url, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo("pong")
    }

    @After
    fun stopContainerGracefully() {
        appContainer.dockerClient.stopContainerCmd(appContainer.containerId).withTimeout(10).exec()
    }

}

// workaround for Kotlin and testcontainers
// https://github.com/testcontainers/testcontainers-java/issues/318
class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)
