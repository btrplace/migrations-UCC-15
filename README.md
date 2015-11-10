# Reproducible migrations experiments

This repository aims to provide a basis for reproducible migrations experiments presented on UCC'15 paper "Scheduling Live-Migrations for Fast, Adaptable and Energy Efficient Relocation Operations".

## Setup the environment

All the experiments have been executed on the [Grid'5000 infrastructure](https://www.grid5000.fr/mediawiki/index.php/Grid5000:Home), so you must have an account in order to reproduce the experiments.
We used both `Griffon` and `Graphene` clusters from the Nancy site ([network](https://www.grid5000.fr/mediawiki/index.php/Nancy:Network) / [hardware](https://www.grid5000.fr/mediawiki/index.php/Nancy:Hardware)).

### Create a custom image for g5k nodes

Start from a grid'5000 debian release, you can obtain the list of available images from cmdline `kaenv3 -l`, for example select: `wheezy-x64-base`. Then reserve a node and deploy the image, there are a great documentation for that on the [Grid'5000 wiki](https://www.grid5000.fr/mediawiki/index.php/Getting_Started).

Once deployed, modify the `/etc/rc.local` file like [this one](https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/rc.local) , then put [this custom init script](https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/init_once) in `/etc/` 

Finally, install and setup qemu and libvirt.
If you want, a patched version of qemu allowing to retrieve VMs' dirty pages informations is available [here](https://github.com/btrplace/qemu-patch). Simply follow the informations on the [wiki page](https://github.com/btrplace/qemu-patch/wiki) to see how it works.

Then, modify the libvirt daemon config file (`/etc/libvirt/libvirtd.conf`) and set the following options:

``` txt
listen_tls = 0
listen_tcp = 1
listen_addr = "0.0.0.0"
max_clients = 1000
max_queued_clients = 10000
min_workers = 10
max_workers = 100
max_requests = 100
max_client_requests = 50
```

This allows to make direct TCP connections for the migrations instead of SSH or TLS (slower), and to effectively manage concurrent migrations from one or multiple clients.

Also, make sure you have the following options set in the SSH client config file (`/etc/ssh/ssh_config`) to avoid any warnings due to connections on cloned servers having the same key:

``` txt
HashKnownHosts no
StrictHostKeyChecking no
UserKnownHostsFile /dev/null
LogLevel quiet
```

That's all, now you can save your new image using the [tgz-g5k tool](https://www.grid5000.fr/mediawiki/index.php/TGZ-G5K) for example:

``` shell
tgz-g5k > /tmp/custom_image.tgz
```

Finally register your new image by following the few steps described [here] (https://www.grid5000.fr/mediawiki/index.php/Deploy_environment-OAR2#Describe_the_newly_created_environment_for_deployments).

### Create a custom VM image

The VM image used for the experiments was an Ubuntu 14.10 desktop distribution (RAW img format), the VM configuration (network, ssh, ...) will be automatically done from the environment deployment scripts described later.
Thus you just have to provide a RAW .img file of the operating system you want.

## Deploy the environment

### Reserve nodes

For all the experiments you'll need to reserve nodes *and* some private network adresses, a  /22 is fine, for example:

``` shell
oarsub -l slash_22=1+{"cluster='griffon'"}nodes=12,walltime=2:0:0 -t deploy /path/to/sleeping-script
```

Generally the script `/path/to/sleeping-script` contains an 'infinite sleeping loop' that allows you to keep your reservation alive throughout the duration of the reservation.

### Deploy the custom image on nodes

Retrieve the list of reserved nodes, and deploy your image:

``` shell
oarprint host > files/nodes
kadeploy3 -e <custom_image> -f ./files/nodes  -o ./files/nodes_ok -k
```

### Environment configuration

First, retrieve the UCC'15 version of the deployment scripts from [this repository](https://github.com/vincent-k/scripts-g5k):

``` shell
git clone -b ucc-15 https://github.com/vincent-k/scripts-g5k.git
cd scripts-g5k
```

Then, edit the `config` file, here are the options you have to care about:

* `VM_VPU`
* `VM_MEM`
* `NB_VMS_PER_NODE`
* `VM_BASE_IMG`

Define a controller node, an NFS node, hosting and idle nodes by adding servers hostname to the appropriate files, for example:

``` shell
# Define controler node, NFS server node, hosting and idle nodes
head -1 files/nodes_ok > ./files/ctl_node
head -2 | tail -1 files/nodes_ok > ./files/nfs_srv
tail -n+3 | head -5 files/nodes_ok > files/hosting_nodes
tail -5 files/nodes_ok > files/idle_nodes
```

In this example, the first node will be the controler, the second node the NFS server and the next 5 nodes will host the VMs.

Then populate the other files, it simply consists to create a global list of active nodes (hosting + idle) and to retrieve the list of reserved ip<->mac addresses. It can be done like this:

``` shell
# Populate the global list of 'active' nodes and the list of reserved ips<->macs addresses
cat files/hosting_nodes files/idle_nodes > files/nodes_list
g5k-subnets -im > ./files/ips_macs

# Also ensure these two files are empty
echo -n > files/ips_names
echo -n > files/vms_ips
```

### Setup everything:

The following script will take care about everything:

``` shell
/bin/bash configure_envionment.sh
```

* Configure all nodes (Infiniband, NFS share, BMC, ..).
* Start all VMs on hosting nodes and wait until they are booted.

## Launch the experimentations

### Get or generate the JSON file of the experiment you want to replay

The scenarios JSON files for each experiments can be directly retrieved [here](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15).

Altenatively, you can regenerate them from this repository. Just do the following:

Requirements:
* JDK 8+
* maven 3+

``` shell
# Get and compile the UCC'15 version of the BtrPlace scheduler
git clone -b ucc-15 --single-branch --depth 1 https://github.com/btrplace/scheduler-devs.git
cd scheduler-devs
mvn -Dmaven.test.skip=true install
cd ../

# Generate all experiments JSON files (use at least 2G for JVM memory allocation pool) 
git clone --depth 1 https://github.com/btrplace/migrations-UCC-15.git
cd migrations-UCC-15
MAVEN_OPTS="-Xmx2G -Xms2G" mvn compiler:testCompile surefire:test
```

### Get the BtrPlace plan executor for g5k

Requirements:
* JDK 8+
* maven 3+

Retrieve the executor from [this repository](https://github.com/btrplace/g5k-executor) and compile it:

``` shell
git clone -b ucc-15 https://github.com/btrplace/g5k-executor.git
cd g5k-executor
mvn -Dmaven.test.skip=true package
```

A distribution tarball is generated into the `target` folder, you can now extract and start to use the executor. For example execute it as is to show the cmdline options:

``` shell
cd target
tar xzf g5k-1.0-SNAPSHOT-distribution.tar.gz
cd g5k-1.0-SNAPSHOT/
./g5kExecutor
```

The output should be:

``` txt
Option "-i (--input-json)" is required
g5kExecutor [-d scripts_dir] (-mvm|-buddies -p <x>) -i <json_file> -o <output_file>
 -buddies (--memory-buddies-scheduler) : Select the scheduler of Memory buddies
 -d (--scripts-dir) VAL                : Scripts location relative directory
 -i (--input-json) VAL                 : The json reconfiguration plan to read
                                         (can be a .gz)
 -mvm (--mvm-scheduler)                : Select the scheduler of mVM (default
                                         choice)
 -o (--output-csv) VAL                 : Print actions durations to this file
```

Finally, you'll just need to edit the migration script `g5k-1.0-SNAPSHOT/scripts/migrate.sh` and modify the variable `VM_BASE_IMG` to match your custom VM image location.

### Prepare the scenario execution

The `g5k-1.0-SNAPSHOT/scripts/translate` file must be modified to allow to translate VMs and g5k nodes names into the BtrPlace internal VMs and nodes names, like this:

``` txt
vm-1 vm#0
vm-2 vm#1
...
griffon-60 node#0
griffon-61 node#1
...
```

Start trafic shaping if necessary by executing the script `trafic_shaping.sh` on desired nodes, it can be retrieved [here](https://github.com/btrplace/migrations-UCC-15/blob/master/scripts).

### Start the reconfiguration plan execution:

Each experiment must be started **from the controler node**, there are some usage examples:

**mVM**:

``` shell
./g5kExecutor --mvm-scheduler --input-json <JSON_FILE> --output-csv <OUTPUT_CSV>
```

**MB-2**:

``` shell
./g5kExecutor --memory-buddies-scheduler --parallelism 2 --fixed-order -i <JSON_FILE> -o <OUTPUT_CSV>
```

**MB-3**:

``` shell
./g5kExecutor -buddies -p 3 -f -i <JSON_FILE> -o <OUTPUT_CSV>
```


The `<OUTPUT_CSV>` file contains 3 fields: `ACTION;START;END` where `ACTION` corresponds the BtrPlace String representation of the action, `START` and `END` correspond respectively to the start and end time of the action in the form of timestamps.
