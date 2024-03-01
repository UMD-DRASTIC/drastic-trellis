# How to Install for DEV on K8s

We are using the K8sandra ("Kate" "Sandra") Operator to manage the Cassandra deployment for Trellis LDP. Some of their kubernetes cluster setup scripts will be used here to setup our kubernetes cluster, both for Cassandra and for our stateless applications.

For development on a local machine we recommend using the *kind* tool to create your k8s cluster. You can use the k8ssandra scripts as specified in the [single cluster install guide](https://docs.k8ssandra.io/install/local/single-cluster-helm/).

The K8ssandra Operator is deployed into the new "drastic" k8s namespace via Helm:

```
$ helm install k8ssandra-operator k8ssandra/k8ssandra-operator -n drastic --create-namespace
```

Once your k8s cluster is up and running. Make sure that it is your current kube context before proceeding to install the k8ssandra-operators, following instructions in the guide. The operator will then deploy a Cassandra cluster and related tools, using the configuration file we provide here. It uses a "trellis" namespace for this Cassandra cluster within the k8s cluster. Some additional commands give more details.

```
$ kubectl apply -n drastic -f k8ssandra_trellis_config.yaml 
$ kubectl get k8cs -n drastic
$ kubectl get pods -n drastic
$ kubectl describe k8cs trellis -n drastic
$ CASS_USERNAME=$(kubectl get secret trellis-superuser -n drastic -o=jsonpath='{.data.username}' | base64 --decode) \
  CASS_PASSWORD=$(kubectl get secret trellis-superuser -n drastic -o=jsonpath='{.data.password}' | base64 --decode) \
  echo $CASS_PASSWORD \
  echo $CASS_USERNAME
$ kubectl exec -it trellis-dc1-default-sts-0 -n drastic -c cassandra -- nodetool -u $CASS_USERNAME -pw $CASS_PASSWORD status
```

All of the commands above should return details about your new Cassandra cluster for Trellis LDP. For local development purposes the C* cluster has three nodes with 16 data partitions that are shared by all of the nodes. The storage resources dedicated to the cluster are relatively small and easy to overwhelm if you pump many files into Trellis LDP.

## Starting and Stoping Kubernetes w/in Docker

The kind tool installs your K8s cluster within Docker nodes. Here are some commands to stop and start those nodes so that they don't always consume resources on your workstation:

```
$ docker stop $(docker ps -q -f name=k8ssandra -f name=kind-registry)
$ docker start $(docker container ls -a -q -f name=k8ssandra -f name=kind-registry)
```

NOTE: You may find that it takes several minutes for the C* cluster to return to working order after a restart of the k8s cluster on your laptop. You will see errors reported from nodetool until the C* nodes fully recovers to UP/NORMAL or "UN".