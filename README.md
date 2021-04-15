# DRAS-TIC Trellis
This is a prototype stack based on the Trellis LDP project that combines high-throughput
Cassandra distributed storage with a set of extensions for Fedora API compliance and 
distributed workflows through Apache Kafka.

# Cassandra Storage
See the [Trellis Extensions](https://github.com/trellis-ldp/trellis-extensions) project for implementation details.
This storage model was performance tested as part of the [Drastic Fedora](http://drastic-testbed.umd.edu/) IMLS project.

# Fedora 5 Compliance
* Adjusts certain response codes to fit specification
* Honors "Want-Digest" with "no-cache" by calculating on demand using a temporary file
* Tweaks to match Fedora's Memento interaction model
* Limits LDP type "Link" headers to one (uses first one given)

# Kafka Workflows (in development)
* Publishing of activity streams to a Apache Kafka topic (Done)
* Distributed workflow processing on Trellis nodes:
  * Streaming of subscribed object updates via websocket.
  * Initial and periodic digest validation.
  *
* Distributed processing on non-Trellis clients:
  * Python notebook example

# Building Docker Container
The Docker JVM-based container is built by the Gradle assemble task. 

```
$ ./gradlew assemble
```

# Run the Stack
This step requires a Docker Swarm.

```
$ docker stack deploy -c docker-compose.yml drastic
$ docker stack ps drastic
```

The Cassandra database must be initialized before Trellis can use it.
Wait until the *ps* command reports that the cassandra-init service has "Completed" instead of "Failed".
Then you can reach the Trellis LDP service:

```
$ curl -v 0.0.0.0:8080
```