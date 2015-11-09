# Reproducible migrations experiments

This repository aims to provide a basis for reproducible migrations experiments presented on UCC'15 paper "Scheduling Live-Migrations for Fast, Adaptable and Energy Efficient Relocation Operations"

## Setup the environment

### Create a custom image for g5k nodes

Start from a g5k debian release: `kaenv3 -l`. For example select: `wheezy-x64-base`

Once done, modify the `/etc/rc.local` file like [this one] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/rc.local) , then put [this custom init script] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/init_once) in `/etc/` 

Finally, install and setup qemu and libvirt.
A patched version of qemu that allows to retrieve VMs' dirty pages informations is available [here] (https://github.com/btrplace/qemu-patch). Simply follow the informations on the [wiki page] (https://github.com/btrplace/qemu-patch/wiki) to see how it works.

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

First, edit the `config` file, here are the options you should care about:

* VM_VPU
* VM_MEM
* NB_VMS_PER_NODE
* Vm_BASE_IMG

Define a controller node, an NFS node, hosting and idle nodes:

``` shell
head -1 files/nodes_ok > ./files/ctl_node
head -2 | tail -1 files/nodes_ok > ./files/nfs_srv
tail -n+23 | head -5 files/nodes_ok > files/hosting_nodes
tail -5 files/nodes_ok > files/idle_nodes
cat files/hosting_nodes files/idle_nodes > files/nodes_list
g5k-subnets -im > ./files/ips_macs
echo -n > files/ips_names
echo -n > files/vms_ips
```

In this example, the first node will be the controler, the second node the NFS server and the next 5 nodes will host the VMs.

### Setup everything:

``` shell
/bin/bash configure_envionment.sh
```

## Start experimentations

### Get the BtrPlace plan executor for g5k

Retrieve it from https://github.com/btrplace/g5k-executor and compile it:

``` shell
git clone -b ucc-15 git@github.com:btrplace/g5k-executor.git
cd g5k-executor
mvn clean install
```

A tarball should be generated into the `target` folder, you can then check the executor cmdline options for example:

``` shell
cd target
tar xzf g5k-1.0-SNAPSHOT-distribution.tar.gz
cd g5k-1.0-SNAPSHOT/
./g5kExecutor
```

You'll just need to edit the migration script `src/main/bin/scripts/migrate.sh` and modify the variable `VM_BASE_IMG` to match your custom VM image location.

### Get or generate the JSON file of the experiment you want to replay

The scenarios JSON files for each experiments can be directly retrieved [here] (https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15).

Altenatively, you can regenerate them from this repository. Just do the following:

``` shell
# Get and compile the UCC'15 version of the BtrPlace scheduler
git clone -b ucc-15 --single-branch --depth 1 git@github.com:btrplace/scheduler-devs.git
cd scheduler-devs
mvn clean install -Dmaven.test.skip=true
cd ../

# Generate all experiments JSON files
git clone --depth 1 git@github.com:btrplace/migrations-UCC-15.git
cd migrations-UCC-15
mvn test
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

