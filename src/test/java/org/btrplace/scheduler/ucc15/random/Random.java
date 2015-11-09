package org.btrplace.scheduler.ucc15.random;

import net.minidev.json.JSONObject;
import org.btrplace.json.JSONConverterException;
import org.btrplace.json.plan.ReconfigurationPlanConverter;
import org.btrplace.model.*;
import org.btrplace.model.constraint.Fence;
import org.btrplace.model.constraint.SatConstraint;
import org.btrplace.model.constraint.migration.MinMTTRMig;
import org.btrplace.model.view.ShareableResource;
import org.btrplace.model.view.network.Network;
import org.btrplace.model.view.network.Switch;
import org.btrplace.plan.ReconfigurationPlan;
import org.btrplace.scheduler.choco.DefaultChocoScheduler;
import org.btrplace.scheduler.choco.DefaultParameters;
import org.btrplace.scheduler.choco.transition.MigrateVMTransition;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Created by vkherbac on 19/02/15.
 */
public class Random {

    String path = new File("").getAbsolutePath() + "/src/test/java/org/btrplace/scheduler/tests/ucc15/random/";

    public Map<VM,Node> randomTest(String fileName, Map<VM,Node> placedVMs) {

        // Set nb of nodes and vms
        int nbNodes = 4;
        int nbVMs = 10;

        // Set mem + cpu for Nodes and VMs
        int mem_node = 16, cpu_node = 8;
        int mem_vm1 = 2, cpu_vm1 = 1;
        int mem_vm2 = 3, cpu_vm2 = 1;

        // Set memoryUsed and dirtyRate (for all VMs) => "stress --vm 1000 --bytes 70K"
        int tpl1MemUsed = 2000, tpl1MaxDirtySize = 96, tpl1MaxDirtyDuration = 2; double tpl1DirtyRate = 2;
        int tpl2MemUsed = 3000, tpl2MaxDirtySize = 96, tpl2MaxDirtyDuration = 2; double tpl2DirtyRate = 2;

        // New default model
        Model mo = new DefaultModel();
        Mapping ma = mo.getMapping();

        // Create online nodes
        List<Node> nodes = new ArrayList<>();
        for (int i=0; i<nbNodes; i++) { nodes.add(mo.newNode()); ma.addOnlineNode(nodes.get(i)); }

        // Add resource views
        ShareableResource rcMem = new ShareableResource("mem", 0, 0);
        ShareableResource rcCPU = new ShareableResource("cpu", 0, 0);
        for (Node n : nodes) { rcMem.setCapacity(n, mem_node); rcCPU.setCapacity(n, cpu_node); }
        mo.attach(rcMem); mo.attach(rcCPU);

        // Add attributes
        for (Node n : nodes) { mo.getAttributes().put(n, "boot", 120); /*~2 minutes to boot*/ }
        for (Node n : nodes) {  mo.getAttributes().put(n, "shutdown", 30); /*~30 seconds to shutdown*/ }

        // Init VM <-> Mem mapping for random setup
        Map<VM,Integer> memPerVM = new HashMap<>();

        // Create VMs
        List<VM> vms = new ArrayList<>();
        for (int i=0; i<nbVMs/2; i++) {
            VM v = mo.newVM(); vms.add(v); rcMem.setConsumption(v, mem_vm1); rcCPU.setConsumption(v, cpu_vm1);
            memPerVM.put(v,mem_vm1);
            mo.getAttributes().put(vms.get(i), "memUsed", tpl1MemUsed);
            mo.getAttributes().put(vms.get(i), "dirtyRate", tpl1DirtyRate);
            mo.getAttributes().put(vms.get(i), "maxDirtySize", tpl1MaxDirtySize);
            mo.getAttributes().put(vms.get(i), "maxDirtyDuration", tpl1MaxDirtyDuration);
        }
        for (int i=nbVMs/2; i<nbVMs; i++) {
            VM v = mo.newVM(); vms.add(v); rcMem.setConsumption(v, mem_vm2); rcCPU.setConsumption(v, cpu_vm2);
            memPerVM.put(v,mem_vm2);
            mo.getAttributes().put(vms.get(i), "memUsed", tpl2MemUsed);
            mo.getAttributes().put(vms.get(i), "dirtyRate", tpl2DirtyRate);
            mo.getAttributes().put(vms.get(i), "maxDirtySize", tpl2MaxDirtySize);
            mo.getAttributes().put(vms.get(i), "maxDirtyDuration", tpl2MaxDirtyDuration);

        }

        if (placedVMs.isEmpty()) {
            // Setup an initial placement
            for (VM vm : vms) {
                ma.addRunningVM(vm, nodes.get(vm.id() % nbNodes));
                placedVMs.put(vm, nodes.get(vm.id() % nbNodes));
            }
        }
        else {
            // Setup the input placement
            for (VM vm : placedVMs.keySet()) { ma.addRunningVM(vms.get(vm.id()), nodes.get(placedVMs.get(vm).id())); }
        }

        // Init a list of constraints
        List<SatConstraint> cstrs = new ArrayList<>();

        // Init variables to generate a new random placement
        java.util.Random r = new java.util.Random(); Node n = null;
        Map<VM,Node> newPlacedVMs = new HashMap<>();
        Map<Node,Integer> memPerNode = new HashMap<>();
        for (int i=0; i<nbNodes; i++) { memPerNode.put(nodes.get(i), 0); }

        // Select the destination node randomly
        for (VM vm : placedVMs.keySet()) {

            // Select a destination node != source node AND with enough resources
            do { n = nodes.get(r.nextInt(nodes.size())); }
            while (memPerVM.get(vm) >= (mem_node - memPerNode.get(n)) || n.equals(placedVMs.get(vm)));

            // Force the migration to the selected destination node
            cstrs.add(new Fence(vm, Collections.singleton(n)));
            newPlacedVMs.put(vm, n);
            memPerNode.replace(n, memPerNode.get(n)+memPerVM.get(vm));
        }

        // Create a NetworkView view AND connect half of the nodes to a 500Mb/s link
        Network net = new Network(); Switch swMain = net.newSwitch();
        //Collections.shuffle(nodes);
        for (int i=0; i<nbNodes/2; i++) { net.connect(500, swMain, nodes.get(i)); }
        for (int i=nbNodes/2; i<nbNodes; i++) { net.connect(1000, swMain, nodes.get(i)); }
        mo.attach(net);
        //net.generateDot(path + "topology.dot", false);

        // Set parameters
        DefaultParameters ps = new DefaultParameters();
        ps.setVerbosity(2);
        ps.setTimeLimit(10);
        ps.doOptimize(false);

        // Set the custom migration transition
        ps.getTransitionFactory().remove(ps.getTransitionFactory().getBuilder(VMState.RUNNING, VMState.RUNNING));
        ps.getTransitionFactory().add(new MigrateVMTransition.Builder());

        // Set a custom objective
        DefaultChocoScheduler sc = new DefaultChocoScheduler(ps);
        Instance i = new Instance(mo, cstrs, new MinMTTRMig());

        ReconfigurationPlan p;
        try {
            p = sc.solve(i);
            Assert.assertNotNull(p);

            ReconfigurationPlanConverter planConverter = new ReconfigurationPlanConverter();
            JSONObject obj = null;

            try {
                obj =  planConverter.toJSON(p);
            } catch (JSONConverterException e) {
                System.err.println("Error while converting the plan: " + e.toString());
                e.printStackTrace();
                newPlacedVMs = null;
            }

            try {
                FileWriter file = new FileWriter(path + fileName);
                file.write(obj.toJSONString());
                file.flush();
                file.close();
            } catch (IOException e) {
                System.err.println("Error while writing the plan: " + e.toString());
                e.printStackTrace();
                newPlacedVMs = null;
            }

            //ActionsToCSV.convert(p.getActions(), path + "actions.csv");
            System.err.println(p);
            System.err.flush();

        } catch (Exception e) {
            newPlacedVMs = null;
        } finally {
            //System.err.println(sc.getStatistics());
            return newPlacedVMs;
        }
    }

    @Test
    public void runRandom() {

        int nb = 50;

        Map<VM,Node> mapVMs = new HashMap<>();
        Map<VM,Node> newMapVMs = null;

        for (int i=1; i<=nb; i++) {
            while (newMapVMs == null) { newMapVMs = randomTest("random." + i + ".json", mapVMs); }
            mapVMs.clear(); mapVMs.putAll(newMapVMs);
            newMapVMs.clear(); newMapVMs = null;
        }
    }
}