# Reproducible migrations experiments

This repository aims to provide a basis for reproducible migrations experiments presented on UCC'15 paper "Scheduling Live-Migrations for Fast, Adaptable and Energy Efficient Relocation Operations"

## Setup the environment

### Create a custom image for g5k nodes

Start from a g5k debian release:
`kaenv3 -l`
For example: `wheezy-x64-base`

Once done, modify the `/etc/rc.local` file like [this one] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/rc.local) , then put [this custom init script] (https://github.com/btrplace/migrations-UCC-15/blob/master/images/node/init_once) in `/etc/` 

Finally, install and setup qemu and libvirt.
A patched version of qemu taht allows to retrieve VMs' dirty pages informations is available [here] (https://github.com/btrplace/qemu-patch). Checkout the branch 'ucc-15' and follow informations on the wiki page to see how it works.

### Create a custom VM image

It can be whatever you want, just select THE operating system you master.
Then add the init script <TO PROVIDE on subdir>

## Deploy

### Reserve nodes

You'll need to reserve nodes *and* some private network adresses, a  /22 is fine:

`oarsub -l slash_22=1+{"cluster='griffon'"}nodes=12,walltime=2:0:0 -t deploy /path/to/sleeping-script`

### Deploy custom image on nodes

Retrieve the list of reserved nodes:
`oarprint host > files/nodes`
`kadeploy3 -e debian-sid-qemu -f ./files/nodes  -o ./files/nodes_ok -k`

### Environment configuration

Get the deployment script from https://github.com/vincent-k/scripts-g5k
Checkout the branch 'ucc-15'

First, edit the `config` file, here are the options you should care about:

* VM_VPU
* VM_MEM
* NB_VMS_PER_NODE
* Vm_BASE_IMG

Define a controller node, an NFS node, hosting and idle nodes:

`head -1 files/nodes_ok > ./files/ctl_node`
`head -2 | tail -1 files/nodes_ok > ./files/nfs_srv`
`tail -n+23 | head -5 files/nodes_ok > files/hosting_nodes`
`tail -5 files/nodes_ok > files/idle_nodes`
`cat files/hosting_nodes files/idle_nodes > files/nodes_list`
`g5k-subnets -im > ./files/ips_macs`
`echo -n > files/ips_names`
`echo -n > files/vms_ips`

### Setup everything:

`/bin/bash configure_envionment.sh`

## Start experimentations

### Get the BtrPlace plan executor for g5k

Retrieve it from https://github.com/btrplace/g5k-executor and compile it:

`mvn clean install`
`tar xzf g5k-1.0-SNAPSHOT-distribution.tar.gz`

Edit the migration script `src/main/bin/scripts/migrate.sh` and modify the variable `VM_BASE_IMG` to match your custom VM image location.

### Prepare the scenario execution

The `translate` file must be modified to translate VMs and g5k nodes names in order to reflect BtrPlace internal VMs and nodes names:

`vm-1 vm#0`
`vm-1 vm#0`
...
`griffon-60 node#0`
`griffon-61 node#1`
...

The scenarios JSON files can be retrieve here: <repo subfolder>

Start trafic shaping if necessary using the script `trafic_shaping.sh` available here https://github.com/btrplace/migrations-UCC-15/blob/master/scripts/trafic_shaping.sh

### Start the reconfiguration plan execution:

The scenario must be started from the controler node:

mVM:
`./g5kExecutor --mvm-scheduler --input-json ./JSON_FILE --output-csv ./OUTPUT_CSV`

MB-2:
`./g5kExecutor --memory-buddies-scheduler --parallelism 2 --fixed-order -i ./JSON_FILE -o ./OUTPUT_CSV`

MB-3:
`./g5kExecutor -buddies -p 3 -f -i ./JSON_FILE -o ./OUTPUT_CSV`

...

