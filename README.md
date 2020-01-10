# DRAS-TIC Trellis
This is a custom stack based on the Trellis LDP project that combined high performance
Cassandra-based storage with a set of Fedora API compliance extensions that make Trellis
more suitable for various Fedora 5-based applications.

# Run the Stack
```
$ docker stack deploy -c docker-compose-dev.yml trellis
$ docker stack ps trellis
#  wait until cassandra-init nodes says "Completed" instead of "Failed"
$ curl -v 0.0.0.0:8080
```

# Building Docker Container

```
$ ./gradlew clean assemble
$ docker build -f src/main/docker/Dockerfile.jvm -t gregjan/drastic-trellis .
```
