# Reproducible migrations experiments

This repository aims to provide a basis for reproducible migrations experiments presented on [UCC'15 conference](http://cyprusconferences.org/ucc2015) paper "Scheduling Live-Migrations for Fast, Adaptable and Energy Efficient Relocation Operations".



## List of experiments

Here is the structure of the Java `src` directory (JSON and CSV files are not shown):

``` txt
src
└── test
    └── java
        └── org
            └── btrplace
                └── scheduler
                    └── ucc15
                        ├── capping
                        │   ├── mVM.java
                        ├── energy
                        │   ├── BtrPlace.java
                        │   ├── mVM.java
                        ├── random
                        │   └── Random.java
                        └── scale
                            ├── BtrPlace.java
                            └── mVM.java
```

* `Performance` experiments presented on section `V.B.` are available in the [`random` directory](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15/random).
* `Energy` experiments presented on section `V.C.1)` are available in the [`energy` directory](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15/energy).
* `Power capping` experiments presented on section `V.C.2)` are available in the [`capping` directory](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15/capping).
* `Scalability` experiments presented on section `V.D.` are available in the [`scale` directory](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15/scale).

You can either chose to use the provided JSON files to execute the experiments or to generate them by yourself using the corresponding java test classes (procedure described below).



## Setup the environment

All the experiments have been executed on the [Grid'5000 infrastructure](https://www.grid5000.fr/mediawiki/index.php/Grid5000:Home) (g5k), so you must have an account in order to reproduce the experiments.
We used both `Griffon` and `Graphene` clusters from the Nancy site ([network](https://www.grid5000.fr/mediawiki/index.php/Nancy:Network)/[hardware](https://www.grid5000.fr/mediawiki/index.php/Nancy:Hardware)).


### Create a custom image for g5k nodes

Start from a g5k debian release, you can obtain the list of available images from cmdline `kaenv3 -l`, for example select: `wheezy-x64-base`. Then reserve a node and deploy the desired image on it, there is a great documentation for that on the [Grid'5000 wiki](https://www.grid5000.fr/mediawiki/index.php/Getting_Started).

Once deployed, edit the `/etc/rc.local` file and add the following code at the bottom (before `exit 0`):

``` shell
# Load appropriate KVM kernel modules :
modprobe kvm
[ $(cat /proc/cpuinfo | grep Intel | wc -l) -gt 0 ] &&  modprobe kvm_intel || modprobe kvm_amd

# Load nbd driver for qemu utils
modprobe nbd max_part=4

# Launch a custom script and remove it once done
[ -f /etc/init_once ] &&  /etc/init_once && rm /etc/init_once
```

This will load the KVM kernel modules on boot and execute a custom script (`/etc/init_once`) on first boot.

The script `/etc/init_once` will configure the network and generate a uniq libvirt uuid on first boot, make sure the file is executable after creating it:

``` shell
#!/bin/bash

# Create a bridge br0 and attach eth0 to it
virsh iface-bridge eth0 br0
brctl stp br0 off # Disable spanning tree
brctl setfd br0 0 # Set the forwarding delay to 0

# Modify the routing
NEW_ROUTE=$(echo `ip route | head -1` | sed 's/eth0/br0/g')
ip route del `ip route | head -1`
ip route del `ip route | head -1`
ip route add $NEW_ROUTE

# Generate a random UUID to differenciate libvirt hosts
sed -i "s/#host_uuid = .*/host_uuid = \"`uuidgen`\"/g" /etc/libvirt/libvirtd.conf
/etc/init.d/libvirt-bin restart

exit 0
```

Then, install and setup qemu and libvirt.
A patched version of qemu that allows to retrieve VMs' dirty pages informations is available [here](https://github.com/btrplace/qemu-patch), simply follow the informations on the [wiki page](https://github.com/btrplace/qemu-patch/wiki) to see how it works.

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

Then, make sure you have the following options set in the SSH client config file (`/etc/ssh/ssh_config`) to avoid any warnings due to connections on cloned servers having the same key:

``` txt
HashKnownHosts no
StrictHostKeyChecking no
UserKnownHostsFile /dev/null
LogLevel quiet
```

Be sure you configured **SSH access from your account**, and that's all!

You can now save your new image using the [tgz-g5k tool](https://www.grid5000.fr/mediawiki/index.php/TGZ-G5K), for example:

``` shell
tgz-g5k > /tmp/custom_image.tgz
```

Finally, register your new image by following the few steps described [here] (https://www.grid5000.fr/mediawiki/index.php/Deploy_environment-OAR2#Describe_the_newly_created_environment_for_deployments).

**Note**: All the modified configurations files and scripts described above are available [here](https://github.com/btrplace/migrations-UCC-15/tree/master/configs).


### Create a custom VM image

The VM image that we used for all the experiments was an **Ubuntu 14.10 desktop** distribution.
Feel free to create your own in a **RAW img file**, you only need to install the `stress` tool to be able to simulate memory intensive workloads.
Also, make sure that no memory intensive application run on start-up, as they can cause longer migration durations.
The overall VM configuration (network, ssh, ...) will be done automatically by the given deployment scripts.



## Deploy the environment


### Reserve nodes

For all the experiments you'll need to reserve nodes **and** some private network adresses, a  /22 is fine, for example:

``` shell
oarsub -l slash_22=1+{"cluster='griffon'"}nodes=12,walltime=2:0:0 -t deploy /path/to/sleeping-script
```

Generally the script `/path/to/sleeping-script` contains an *infinite sleeping loop* that allows you to keep your reservation alive throughout the whole reservation duration.

### Deploy the custom image on nodes

Retrieve the list of reserved nodes, and deploy your image:

``` shell
oarprint host > files/nodes
kadeploy3 -e <custom_image> -f ./files/nodes  -o ./files/nodes_ok
```


### Environment configuration

First, retrieve the deployments scripts located in the local [`utils/scripts-g5k` subfolder](https://github.com/btrplace/migrations-UCC-15/tree/master/utils/scripts-g5k).

Alternatively, you can retrieve them from the [original repository](https://github.com/vincent-k/scripts-g5k) like this:

``` shell
git clone -b ucc-15 --single-branch --depth 1 https://github.com/vincent-k/scripts-g5k.git
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



## Prepare the experiment


### Get the BtrPlace plan executor for g5k

Requirements:
* JDK 8+
* maven 3+

First, retrieve and compile the UCC'15 version of the BtrPlace scheduler:

``` shell
git clone --depth 1 https://github.com/btrplace/scheduler-UCC-15.git
cd scheduler-UCC-15
mvn -Dmaven.test.skip=true install
cd ../
```

Then, retrieve the UCC'15 version of the g5k executor from [this repository](https://github.com/btrplace/g5k-executor) and compile it:

``` shell
git clone -b ucc-15 --single-branch --depth 1 https://github.com/btrplace/g5k-executor.git
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


### Get or generate the desired JSON files

Each JSON file describes a full reconfiguration plan of a particular scenario generated by the BtrPlace scheduler, they can be directly retrieved [here](https://github.com/btrplace/migrations-UCC-15/tree/master/src/test/java/org/btrplace/scheduler/ucc15).

Alternatively, you can regenerate them from the current git repository, just do the following:

``` shell
# Generate all experiments JSON files (use at least 2G for JVM memory allocation pool) 
git clone --depth 1 https://github.com/btrplace/migrations-UCC-15.git
cd migrations-UCC-15
MAVEN_OPTS="-server -Xmx2G -Xms2G" mvn compiler:testCompile surefire:test
```


### Prepare the VMs

As the intial placement may not correspond to the experiment setup, be sure to migrate each VM to its desired hosting node. You can use the `migrate.sh` script from `g5-executor`, for example:

``` shell
./g5k-1.0-SNAPSHOT/scripts/migrate.sh vm-1 griffon-1 griffon-2 1000 "-- live --verbose"
```

This will migrate `vm1` from `griffon-1` to `griffon-2` in live at 1 Gbit/sec with a real time progression overview.

Then, make each VM consume the desired amount of memory and, if needed, execute a workload on appropriate VMs.

A simple solution consists to set the memory allocated to the VM at the desired value and then make it consume its full amount of memory by using the `stress` cmd.

For example, to allocate a maximum of 4 GiB of memory to the VM `vm-1`:

``` shell
virsh -c <hosting_node> setmem vm-1 4G
```

Obviously, this amount need to be lower or equal to the memory allocated to the VM at its creation (variable `VM_MEM` in the deployment `config` file).

Then you can use `stress` to consume some memory, to verify how much memory consume a VM use:

``` shell
virsh -c <hosting_node> dommemstat vm-1
```

Look at the field `rss` (*Resident Set Size*) that represents, in KiB, how much memory is allocated to the VM **and** is actually in RAM.

Then launch the workload on appropriate VMs as described in the paper, for example:

``` shell
stress --vm 1000 --bytes 50K
```

This command will run 1000 concurrent threads that will continuously allocate and free up 50 KiB of memory each.


### Final preparations

The `g5k-1.0-SNAPSHOT/scripts/translate` file must be filled to translate VMs and g5k nodes names into the BtrPlace VMs and nodes names, it should look like this:

``` txt
vm-1    vm#0
vm-2    vm#1
...
griffon-60  node#0
griffon-61  node#1
...
```

Each entry must contains first the real node/VM name and then the corresponding BtrPlace name **separated by a tabulation**.
The BtrPlace numbering is defined incrementally according to the node/VM creation order (see the corresponding Java test classes to verify the order).

If necessary, start the traffic shaping on desired nodes by executing the script `traffic_shaping.sh` located in the [sub-directory `utils/`](https://github.com/btrplace/migrations-UCC-15/blob/master/utils).
The default parameters will limit the bandwidth to 500 Mbit/sec which corresponds to the traffic shaping applied in the `random` experiment.



## Start the execution

Each experiment must be started **from the controler node**, here are some usage examples:

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
