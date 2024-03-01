This chart is configurable to use Cassandra or PostgreSQL as the backend storage for the Trellis LDP service. Cassandra is the storage option that "invites computation" with support for high throughput and horizontal scaling. PostgreSQL is suitable for a lower throughput repository with simpler technical administrative needs. It is also suitable for development on a typical workstation.

This charts creates the following services:

* Trellis LDP, including:
  * PostgreSQL (optional)
  * Custom DRASTIC modules, see DRASTIC Features
* Kafka, including
  * Zookeeper  (TODO: remove dependency as per latest Kafka builds)
  * Kafdrop
* Apache Fuseki
* Apache Tika
* Elasticsearch


This chart was created initially by Kompose
