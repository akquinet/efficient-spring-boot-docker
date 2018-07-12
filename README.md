
# Efficient docker images for Spring Boot applications

## tldr
Using fat JARs within docker images wastes storage. 
I'll demonstrate how to do better when using Spring Boot and Maven.

## Introduction
Spring Boot apps are usually packaged as fat JARs, that contain all runtime dependencies (besides the JVM).
This is convenient as you only need a Java runtime and a single JAR to run the application.
If you are into docker for your production environment, you cannot run them directly, but you have to build an docker image.

Doing this in case of Spring Boot is simple. 
Just take an java docker image, copy the fat JAR and set a run command to start your application.

Doing so creates a new layer on the base image. 
This layer contains all your stuff, even those things that don't change very often.
If we build the image multiple times we create layers with partially the same content every time.

We can optimize storage usage by copying stuff in several steps.
Things that change less frequently should be copied before the stuff that changes often.
A good candidate are third party dependencies.
There is usually plenty of them, and they can be removed from the JAR easily (by not adding them ;-).

The idea that normal JARs are a better fit for docker is not new, 
but I did not find a concise description for my use case.
That's why I will show you how to go thin with a Spring Boot app build by Maven in 4 simple steps.
But first I'll briefly describe the setup. Skip to [Going thin](#going-thin) if you are in a hurry.

## The sample app
I created a sample project from [https://start.spring.io/](https://start.spring.io/) using Maven and Kotlin for the build settings, and _Web_ as a dependency.
I added a single controller that returns the text _pong_ when you [GET /ping](http://localhost:8080/ping).

To make creating the image simpler I set the Maven artifact name to _app.jar_ and also added the [dockerfile-maven-plugin](https://github.com/spotify/docker-maven-plugin):
```xml
<build>
    <finalName>app</finalName>
    ...
    <plugins>
        ...
        <plugin>
          <groupId>com.spotify</groupId>
          <artifactId>dockerfile-maven-plugin</artifactId>
          <version>1.4.3</version>
          <executions>
              <execution>
                  <id>default</id>
                  <goals>
                      <goal>build</goal>
                  </goals>
              </execution>
          </executions>
          <configuration>
              <repository>${project.artifactId}</repository>
          </configuration>
        </plugin>
    </plugins>
</build>
```

This is the Dockerfile
```dockerfile
FROM openjdk:8u171-jdk-alpine3.7

CMD ["/usr/bin/java", "-jar", "/app.jar"]

COPY target/app.jar /app.jar
```

The image is named _spring-docker-demo_ and can be build by calling
```bash
./mvnw clean package

# and run it with
docker run -it --rm -p 8080:8080 spring-docker-demo
```

To check that everything works, I added a system test that starts the container and calls ping.

```kotlin
class DemoApplicationIT {

    @get:Rule
    var appContainer = KGenericContainer("spring-docker-demo:latest")
            .waitingFor(Wait.forListeningPort())
            .withExposedPorts(8080)

    val client: RestTemplate = RestTemplateBuilder().build()

    @Test
    fun `can call ping`() {
        val url = "http://localhost:${appContainer.getMappedPort(8080)}/ping"

        val response = client.getForEntity(url, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo("pong")
    }
}
```

Run it with ```mvnw verify```.

### Image layers with fat JAR
Below you can find 2 runs of _docker history_ for two different builds.
As you can see, each time a layer of size 19MB got created, while the lower layers stayed the same.
Look at images ids in the first column, to see what has changed and what not.
```
$ docker history spring-docker-demo:latest
  IMAGE               CREATED             CREATED BY                                      SIZE                COMMENT
  8cfb75af53d7        3 seconds ago       /bin/sh -c #(nop) COPY file:e3bc0516cf66c218…   19.7MB
  6f47352b8ca7        4 seconds ago       /bin/sh -c #(nop)  CMD ["/usr/bin/java" "-ja…   0B
  83621aae5e20        2 days ago          /bin/sh -c set -x  && apk add --no-cache   o…   97.4MB
...

$ docker history spring-docker-demo:latest
  IMAGE               CREATED              CREATED BY                                      SIZE                COMMENT
  253fe4caaf81        4 seconds ago        /bin/sh -c #(nop) COPY file:cac47cad92ce86f5…   19.7MB
  6f47352b8ca7        About a minute ago   /bin/sh -c #(nop)  CMD ["/usr/bin/java" "-ja…   0B
  83621aae5e20        2 days ago           /bin/sh -c set -x  && apk add --no-cache   o…   97.4MB
...
```

## Going thin
First, we disable Spring Boot repacking by removing the spring-boot-maven-plugin.
```xml
<plugins>
   <!--<plugin>-->
       <!--<groupId>org.springframework.boot</groupId>-->
       <!--<artifactId>spring-boot-maven-plugin</artifactId>-->
   <!--</plugin>-->
    ...
</plugins>
```
Then we copy the dependencies to _./target/dependency_ from where docker can access them.
Only runtime dependencies (i.e. no test scoped dependencies) are needed.
```xml
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-dependencies</id>
            <goals>
                <goal>copy-dependencies</goal>
            </goals>
            <configuration>
                <includeScope>runtime</includeScope>
            </configuration>
        </execution>
    </executions>
</plugin>
```
Make the JAR executable by defining a main class and a directory for the third party dependencies.
```xml
<plugin>
    <artifactId>maven-jar-plugin</artifactId>
    <configuration>
        <archive>
            <manifest>
                <addClasspath>true</addClasspath>
                <classpathPrefix>lib/</classpathPrefix>
                <mainClass>com.example.demo.DemoApplicationKt</mainClass>
            </manifest>
        </archive>
    </configuration>
</plugin>
```

The new Dockerfile first copies the dependencies and then our app.jar.
```dockerfile
FROM openjdk:8u171-jdk-alpine3.7

CMD ["/usr/bin/java", "-jar", "/app.jar"]

COPY target/dependency /lib

COPY target/app.jar /app.jar
```

The test still runs. 
Seems like it is done. 
But lets make sure by looking at the layers again.

### Image layers with thin JAR
Below we look again at the history of two different builds. 
As you can see the new layer created is only kB in size. 
The second layer is larger, but has been build only once.
```
$ docker history spring-docker-demo:latest
  IMAGE               CREATED             CREATED BY                                      SIZE                COMMENT
  f5367cd66280        3 seconds ago       /bin/sh -c #(nop) COPY file:954dc1d547e75958…   6.04kB
  3189147de421        3 seconds ago       /bin/sh -c #(nop) COPY dir:0101e01ecc142390d…   19.6MB
  6f47352b8ca7        2 minutes ago       /bin/sh -c #(nop)  CMD ["/usr/bin/java" "-ja…   0B
  83621aae5e20        2 days ago          /bin/sh -c set -x  && apk add --no-cache   o…   97.4MB
...

$ docker history spring-docker-demo:latest
  IMAGE               CREATED             CREATED BY                                      SIZE                COMMENT
  3bdd09f09486        2 seconds ago       /bin/sh -c #(nop) COPY file:8e5eb913c41a1cf0…   6.04kB
  3189147de421        45 seconds ago      /bin/sh -c #(nop) COPY dir:0101e01ecc142390d…   19.6MB
  6f47352b8ca7        2 minutes ago       /bin/sh -c #(nop)  CMD ["/usr/bin/java" "-ja…   0B
  83621aae5e20        2 days ago          /bin/sh -c set -x  && apk add --no-cache   o…   97.4MB
...
```

## Conclusion
Saving a few MB in a world where we talk about TB might seem irrelevant, 
but if you look at real world examples the savings will be bigger.
Also talk to your ops guy and ask him if disk space on his servers is for free ;-).
If you don't care about storage space, keep using the fat JAR, as the build is a little bit simpler.
