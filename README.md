# Reproducible migrations experiments

This repository aims to provide a basis for reproducible migrations experiments presented on UCC'15 paper "Scheduling Live-Migrations for Fast, Adaptable and Energy Efficient Relocation Operations"

## Setup the environment

### Create a custom image for g5k nodes

Start from a g5k debian release: `kaenv3 -l`. For example select: `wheezy-x64-base`

Once done, modify the `/etc/rc.local` file like [this one] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/rc.local) , then put [this custom init script] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/init_once) in `/etc/` 

Finally, install and setup qemu and libvirt.
If you want, a patched version of qemu allowing to retrieve VMs' dirty pages informations is available [here] (https://github.com/btrplace/qemu-patch). Simply follow the informations on the [wiki page] (https://github.com/btrplace/qemu-patch/wiki) to see how it works.

### Create a custom VM image

It can be whatever you want, just select THE operating system you master.
Then add the init script <TO PROVIDE on subdir>

## Deploy

### Reserve nodes

You'll need to reserve nodes *and* some private network adresses, a  /22 is fine:

``` shell
oarsub -l slash_22=1+{"cluster='griffon'"}nodes=12,walltime=2:0:0 -t deploy /path/to/sleeping-script
```

### Deploy custom image on nodes

Retrieve the list of reserved nodes:

``` shell
oarprint host > files/nodes
kadeploy3 -e debian-sid-qemu -f ./files/nodes  -o ./files/nodes_ok -k
```

### Environment configuration

Get the deployment script from https://github.com/vincent-k/scripts-g5k
Checkout the branch 'ucc-15'

First, edit the `config` file, here are the options you have to care about:

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

``` shell
/bin/bash configure_envionment.sh
```

## Start experimentations

### Get the BtrPlace plan executor for g5k

Retrieve it from https://github.com/btrplace/g5k-executor and compile it:

``` shell
git clone -b ucc-15 https://github.com/btrplace/g5k-executor.git
cd g5k-executor
mvn clean install
```

A distribution tarball is generated into the `target` folder, you can start to use the executor for example to check the cmdline options :

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

Finally, you'll just need to edit the migration script `src/main/bin/scripts/migrate.sh` and modify the variable `VM_BASE_IMG` to match your custom VM image location.

### Get or generate the JSON file of the experiment you want to replay

The scenarios JSON files for each experiments can be directly retrieved [here] (https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15).

Altenatively, you can regenerate them from this repository. Just do the following:

``` shell
# Get and compile the UCC'15 version of the BtrPlace scheduler
git clone -b ucc-15 --single-branch --depth 1 https://github.com/btrplace/scheduler-devs.git
cd scheduler-devs
mvn clean install -Dmaven.test.skip=true
cd ../

# Generate all experiments JSON files (use at least 2G for JVM memory allocation pool) 
git clone --depth 1 https://github.com/btrplace/migrations-UCC-15.git
cd migrations-UCC-15
MAVEN_OPTS="-Xmx2G -Xms2G" mvn compiler:testCompile surefire:test
```

### Prepare the scenario execution

The `translate` file must be modified to translate VMs and g5k nodes names in order to reflect BtrPlace internal VMs and nodes names:

``` txt
vm-1 vm#0
vm-1 vm#0
...
griffon-60 node#0
griffon-61 node#1
...
```

Start trafic shaping if necessary using the script `trafic_shaping.sh` available [here] (https://github.com/btrplace/migrations-UCC-15/blob/master/scripts)

### Start the reconfiguration plan execution:

The scenario must be started from the controler node:

mVM:
``` shell
./g5kExecutor --mvm-scheduler --input-json ./JSON_FILE --output-csv ./OUTPUT_CSV
```

MB-2:
``` shell
./g5kExecutor --memory-buddies-scheduler --parallelism 2 --fixed-order -i ./JSON_FILE -o ./OUTPUT_CSV
```

MB-3:
``` shell
./g5kExecutor -buddies -p 3 -f -i ./JSON_FILE -o ./OUTPUT_CSV
```

...

