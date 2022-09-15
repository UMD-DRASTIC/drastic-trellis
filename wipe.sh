#!/bin/sh
rm --preserve-root -rf docker_stack_vols/cassandra-data/*
rm --preserve-root -rf docker_stack_vols/elasticsearch-data/*
rm --preserve-root -rf docker_stack_vols/fuseki-data/DB2/*
rm --preserve-root -rf docker_stack_vols/kafka-data/*
rm --preserve-root -rf docker_stack_vols/trellis_data/*
rm --preserve-root -rf docker_stack_vols/trellis_log/*
rm --preserve-root -rf docker_stack_vols/zookeeper-data/data/*
rm --preserve-root -rf docker_stack_vols/zookeeper-data/txn-logs/*
