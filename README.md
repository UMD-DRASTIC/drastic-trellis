# DRAS-TIC Trellis
This is a prototype stack based on the Trellis LDP project that combines high-throughput Cassandra distributed storage with a set of extensions for Fedora API compliance and distributed workflows through Apache Kafka. You can also run it against a Postgresql
database for simpler configuration on a single node and for development.
The system combines the strengths of LDP with Kafka event streams, Cassandra high-throughput storage, Fuseki triple store, Elasticsearch, and Apache Tika. A prototype front-end Vue.js application, drastic-dams-ui, is also available as a demonstration of the supported workflows.

## Features

### Cassandra Storage
See the [Trellis Extensions](https://github.com/trellis-ldp/trellis-extensions) project for implementation details.
This storage model was performance tested as part of the [Drastic Fedora](http://drastic-testbed.umd.edu/) IMLS project.
The optional Cassandra storage module dramatically increases write performance for heavy auto-curation workflows.

### Fedora 5 Compliance
This version of Trellis LDP is enhanced with a few HTTP request and response filters
that make it more compliant with the Fedora 5 specification, allowing the use of Fedora
applications. Here is what those filters do:
* Adjust certain response codes to fit specification
* Honor "Want-Digest" with "no-cache" by calculating on demand using a temporary file
* Tweak to match Fedora's Memento interaction model
* Limit LDP type "Link" headers to one (uses first one given)

### Kafka Streaming Workflow
Trellis activity streams are published to an Apache Kafka topic and further Kafka topics
are used to drive all automated curation workflows, including fixity, metadata extraction,
and surrogate image services. Kafka spreads curation loads across the running Trellis
nodes. Each service is part of a named Kafka worker group that prevent duplication receipt.

### Websocket Notications
* HTTP clients can send a subscribe message over websocket to listen to for changes on any LDP
resource. Simple JSON WS messages relay when a subscribed object is changed via any Trellis
node.

### Customizable Folder Workflow
The Drastic service uses these designated LDP folders to store certain types of
resources and apply automated processing to these resources:

- /**description**/ : Nested archival description objects (LDP Basic Container). In the NPS use case these start out as XML exports from their Rediscovery database schema, which are converted to individual linked data resources having an nps:RediscoveryExport type. An automated workflow converts nps:RediscoveryExport predicates into Dublin Core elements. All of these descriptions are then indexed in the Fuseki triple store and in an Elasticsearch index. Fuseki holds an additional graph dedicated to indexing all containment predicates.
  - In addition to normal Dublin Core properties, the Elasticsearch index contains a full text field and some specialized "path" fields to support browsing folders at the point of access.
  - The combined text of most Dublin Core properties are also sent to Apache Tika for extraction of named entities, which are added in Tika namespace triples to the their graphs.

- /**submissions**/ : Each folder under /submissions/ represents a unit of ingest work that brings objects into the repository. Another way to put it is that each sub-folder is a submission information package or SIP in the OAIS model. Raw files of any format can be uploaded within a SIP folder. SIP folders can also be organized into their own nested sub-folders as needed to support workflow requirements.
  - To support the NPS use case, any Excel spreadsheets ending in "_MD5.xlsx" and "inventory.xlsx" are processed to extract fixity information and item-level Dublin Core metadata. These are being appended to each file's descriptive metadata. Files must be added to the SIP first and matching is based on an NPS file naming convention.
  - Images are processed to create access and thumbnail copies with linked between the source and derivative copies.
  - On demand, via a websocket message, the contents of a SIP will be scanned for NPS paged document filename conventions. If a paged document is detected, then a document-level object is added to the SIP, with the pages defined in blank nodes within the document description and linking together all associated page images in order.

- /**name-authority**/ : This folder is for management of name authority records as SKOS records (LDP-RS). Each skos:Concept that is defined in a SKOS resource is indexed in Elasticsearch, having been combined with any other SKOS sources in Fuseki, along with all of the prefLabel and altLabel text values. This is used to support user-assisted and automated tagging of named entities. In the NPS use case, one SKOS file is used to manage a subset of the LOC name authority records that frequently appear in their collections. Another file might be used at some point to record more locally defined altLabels for these LOC concepts. Local altLabels would also be added to the index. Another file might be added at some point for locally defined name authority records.

## Development

### Building Docker Container
The Docker JVM-based container is built by the Gradle assemble task.

```
$ ./gradlew assemble
```

### Run the Stack
This step requires a Docker Swarm.

```
$ docker stack deploy -c docker-stack-dev.yml drastic
$ docker stack ps drastic
```

If you are running the Cassandra database and trellis storage module, the database
must be initialized before Trellis can use it. Wait until the *ps* command reports that the cassandra-init service has "Completed" instead of "Failed". It will attempt to start multiple times until it is
able to contact a Cassandra node and initialize the schema.
Then you can reach the Trellis LDP service:

```
$ curl -v 0.0.0.0:8080
```
