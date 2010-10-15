package edu.mit.hstore;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.voltdb.BackendTarget;
import org.voltdb.ExecutionSite;
import org.voltdb.ProcedureProfiler;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.TransactionIdManager;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Partition;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.InitiateTaskMessage;
import org.voltdb.messaging.TransactionInfoBaseMessage;
import org.voltdb.messaging.VoltMessage;
import org.voltdb.utils.DBBPool;

import ca.evanjones.protorpc.NIOEventLoop;
import ca.evanjones.protorpc.ProtoRpcChannel;
import ca.evanjones.protorpc.ProtoRpcController;
import ca.evanjones.protorpc.ProtoServer;

import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;

import edu.brown.catalog.CatalogUtil;
import edu.brown.markov.EstimationThresholds;
import edu.brown.markov.MarkovGraph;
import edu.brown.markov.MarkovUtil;
import edu.brown.markov.TransactionEstimator;
import edu.brown.utils.ArgumentsParser;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.EventObservable;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.StringUtil;
import edu.brown.utils.ThreadUtil;
import edu.brown.workload.AbstractTraceElement;
import edu.brown.workload.WorkloadTraceFileOutput;
import edu.mit.dtxn.Dtxn;
import edu.mit.dtxn.Dtxn.ExecutionEngine;
import edu.mit.dtxn.Dtxn.FragmentResponse.Status;
import edu.mit.hstore.callbacks.ForwardTxnRequestCallback;
import edu.mit.hstore.callbacks.FragmentResponsePassThroughCallback;
import edu.mit.hstore.callbacks.InitiateCallback;

/**
 * 
 * @author pavlo
 */
public class HStoreCoordinatorNode extends ExecutionEngine implements VoltProcedureListener.Handler {
    private static final Logger LOG = Logger.getLogger(HStoreCoordinatorNode.class.getName());
    protected static final AtomicLong NEXT_TXN_ID = new AtomicLong(1);
    
    private final DBBPool buffer_pool = new DBBPool(true, true);
    private final Map<Integer, Thread> executor_threads = new HashMap<Integer, Thread>();
    private final HStoreMessenger messenger;
    private final TransactionIdManager txnid_manager;
//    private final AtomicInteger fake_evan_txn_ids = new AtomicInteger(0);
    
    // Dtxn Stuff
    private Dtxn.Coordinator coordinator;
    private final NIOEventLoop coordinatorEventLoop = new NIOEventLoop();

    private boolean shutdown = false;
    private final EventObservable shutdown_observable = new EventObservable();
    
    private final Site catalog_site;
    private final Map<Integer, ExecutionSite> executors = new HashMap<Integer, ExecutionSite>();
    private final Database catalog_db;
    private final PartitionEstimator p_estimator;
    private EstimationThresholds thresholds;
    
    /**
     * Status Monitor
     */
    private Thread status_monitor;
    
    /**
     * Count the number of multi-partition txns and single-partitions that we have seen
     */
    private final AtomicInteger singlepart_ctr = new AtomicInteger(0);
    private final AtomicInteger multipart_ctr = new AtomicInteger(0);
    
    /**
     * Keep track of which txns that we have in-flight right now
     */
    private final ConcurrentHashMap<Long, Integer> inflight_txns = new ConcurrentHashMap<Long, Integer>();
    private final AtomicInteger completed_txns = new AtomicInteger(0);

    /**
     * 
     */
    private final ConcurrentHashMap<Long, RpcCallback<byte[]>> client_callbacks = new ConcurrentHashMap<Long, RpcCallback<byte[]>>();
    
    private final ConcurrentHashMap<Long, CountDownLatch> init_latches = new ConcurrentHashMap<Long, CountDownLatch>();
    
    /**
     * Simple Status Printer
     */
    protected class StatusMonitorThread implements Runnable {
        private final int interval; // seconds
        private final String prefix = ">>>>> ";
        private final TreeMap<Integer, TreeSet<Long>> partition_txns = new TreeMap<Integer, TreeSet<Long>>();
        
        private Integer last_completed = null;
        
        private Integer inflight_min = null;
        private Integer inflight_max = null;
        
        public StatusMonitorThread(int interval) {
            this.interval = interval;
            for (Integer partition : CatalogUtil.getAllPartitionIds(HStoreCoordinatorNode.this.catalog_db)) {
                this.partition_txns.put(partition, new TreeSet<Long>());
            } // FOR
            // Throw in -1 for local txns
            this.partition_txns.put(-1, new TreeSet<Long>());
        }
        
        @Override
        public void run() {
            Thread self = Thread.currentThread();
            self.setName(HStoreCoordinatorNode.this.getThreadName("mon"));
            
            LOG.debug("Starting HStoreCoordinator status monitor thread [interval=" + interval + " secs]");
            while (!self.isInterrupted()) {
                try {
                    Thread.sleep(interval * 1000);
                } catch (InterruptedException ex) {
                    return;
                }
                int inflight_cur = inflight_txns.size();
                if (inflight_min == null || inflight_cur < inflight_min) inflight_min = inflight_cur;
                if (inflight_max == null || inflight_cur > inflight_max) inflight_max = inflight_cur;
                
                for (Integer partition : this.partition_txns.keySet()) {
                    this.partition_txns.get(partition).clear();
                } // FOR
                for (Entry<Long, Integer> e : inflight_txns.entrySet()) {
                    this.partition_txns.get(e.getValue()).add(e.getKey());
                } // FOR
                
                int singlep_txns = singlepart_ctr.get();
                int multip_txns = multipart_ctr.get();
                int total_txns = singlep_txns + multip_txns;
                int completed = HStoreCoordinatorNode.this.completed_txns.get();

                StringBuilder sb = new StringBuilder();
                sb.append(prefix).append(StringUtil.DOUBLE_LINE)
                  .append(prefix).append("HstoreCoordinatorNode Status [Site #").append(catalog_site.getId()).append("]\n")
                  .append(prefix).append("InFlight Txn Ids: ").append(String.format("%-4d", inflight_cur)).append(" [")
                                 .append("min=").append(inflight_min).append(", ")
                                 .append("max=").append(inflight_max).append("]\n");
                for (Integer partition : this.partition_txns.keySet()) {
                    int cnt = this.partition_txns.get(partition).size();
                    if (cnt > 0) {
                        sb.append(prefix).append("  Partition[").append(String.format("%02d", partition)).append("]: ")
                          .append(this.partition_txns.get(partition)).append("\n");
                    }
                } // FOR
                
                sb.append(prefix).append("Total Txns:       ").append(String.format("%-4d", total_txns)).append(" [")
                                 .append("singleP=").append(singlep_txns).append(", ")
                                 .append("multiP=").append(multip_txns).append("]\n")
                  .append(prefix).append("Completed Txns: ").append(String.format("%-4d\n", completed))
                  .append(prefix).append(StringUtil.DOUBLE_LINE);
                System.err.print(sb.toString());
                
                if (this.last_completed != null) {
                    // If we're not making progress, bring the whole thing down!
                    if (this.last_completed == completed && inflight_txns.size() > 0) {
                        LOG.fatal("System is stuck! We're going down so that we can investigate!");
                        messenger.shutdownCluster();
                    }
                }
                this.last_completed = completed;
            } // WHILE
        }
    } // END CLASS
    
    /**
     * Shutdown Hook Thread
     */
    protected class ShutdownThread implements Runnable {
        @Override
        public void run() {
            // Dump out our status
            int num_inflight = inflight_txns.size();
            if (num_inflight > 0) {
                System.err.println("FAIL: InFlight Txns Ids => " + inflight_txns.keySet());
            }
        }
    } // END CLASS
    
    /**
     * Constructor
     * @param coordinator
     * @param p_estimator
     */
    public HStoreCoordinatorNode(Site catalog_site, Map<Integer, ExecutionSite> executors, PartitionEstimator p_estimator) {
        // General Stuff
        this.catalog_site = catalog_site;
        this.catalog_db = CatalogUtil.getDatabase(this.catalog_site);
        this.p_estimator = p_estimator;
        this.thresholds = new EstimationThresholds(); // default values
        this.executors.putAll(executors);
        this.messenger = new HStoreMessenger(this, this.executors);
        this.txnid_manager = new TransactionIdManager(this.catalog_site.getId());
        
        assert(this.catalog_db != null);
        assert(this.p_estimator != null);
        
        this.init();
    }
    
    /**
     * Initializes all the pieces that we need to start this HStore site up
     */
    private void init() {
        final boolean debug = LOG.isDebugEnabled(); 
        if (debug) LOG.debug("Initializing HStoreCoordinatorNode...");

        // First we need to tell the HStoreMessenger to start-up and initialize its connections
        if (debug) LOG.debug("Starting HStoreMessenger for Site #" + this.catalog_site.getId());
        this.messenger.start();
        
        // Then we need to start all of the ExecutionSites in threads
        if (debug) LOG.debug("Starting ExecutionSite threads for " + this.executors.size() + " partitions on Site #" + this.catalog_site.getId());
        for (Entry<Integer, ExecutionSite> e : this.executors.entrySet()) {
            Thread t = new Thread(e.getValue());
            e.getValue().setHStoreCoordinatorNode(this);
            e.getValue().setHStoreMessenger(this.messenger);
            this.executor_threads.put(e.getKey(), t);
            t.start();
        } // FOR
        
        // Add in our shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownThread()));
    }

    /**
     * Perform shutdown operations for this HStoreCoordinatorNode
     */
    public synchronized void shutdown() {
        // Tell all of our event loops to stop
        this.coordinatorEventLoop.exitLoop();
        
        // Tell anybody that wants to know that we're going down
        this.shutdown_observable.notifyObservers();
        
        // Tell our local boys to go down too
        for (ExecutionSite executor : this.executors.values()) {
            executor.shutdown();
        } // FOR
    }
    
    /**
     * Get the Oberservable handle for this HStoreCoordinator that can alert
     * others when the party is ending
     * @return
     */
    public EventObservable getShutdownObservable() {
        return shutdown_observable;
    }
    
    
    /**
     * Enable the HStoreCoordinator's status monitor
     * @param interval
     */
    public void enableStatusMonitor(int interval) {
        if (interval > 0) {
            this.status_monitor = new Thread(new StatusMonitorThread(interval));
            this.status_monitor.setPriority(Thread.MIN_PRIORITY);
        }
    }
    
    public void setDtxnCoordinator(Dtxn.Coordinator coordinator) {
        this.coordinator = coordinator;
    }
    
    public Dtxn.Coordinator getDtxnCoordinator() {
        return (this.coordinator);
    }
    
    private void setEstimationThresholds(EstimationThresholds thresholds) {
         this.thresholds = thresholds;
    }
    
    /**
     * Return the Site catalog object for this HStoreCoordinatorNode
     * @return
     */
    public Site getSite() {
        return (this.catalog_site);
    }
    
    public int getSiteId() {
        return (this.catalog_site.getId());
    }
    
    /**
     * Returns a nicely formatted thread name
     * @param suffix
     * @return
     */
    private String getThreadName(String suffix) {
        if (suffix == null) suffix = "";
        if (suffix.isEmpty() == false) suffix = "-" + suffix; 
        return (String.format("H%03d%s", catalog_site.getId(), suffix));
    }
    
    /**
     * Perform final cleanup and book keeping for a completed txn
     * @param txn_id
     */
    public void completeTransaction(long txn_id) {
        this.inflight_txns.remove(txn_id);
        this.completed_txns.incrementAndGet();
        assert(!this.inflight_txns.containsKey(txn_id)) :
            "Failed to remove InFlight entry for txn #" + txn_id + "\n" + inflight_txns;
    }

    @Override
    public void procedureInvocation(byte[] serializedRequest, RpcCallback<byte[]> done) {
        if (this.status_monitor != null && !this.status_monitor.isAlive()) this.status_monitor.start();
        final boolean debug = LOG.isDebugEnabled();
        final boolean trace = LOG.isTraceEnabled();
        
        // The serializedRequest is a ProcedureInvocation object
        StoredProcedureInvocation request = null;
        FastDeserializer fds = new FastDeserializer(serializedRequest);
        try {
            request = fds.readObject(StoredProcedureInvocation.class);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        if (request == null) {
            throw new RuntimeException("Failed to get ProcedureInvocation object from request bytes");
        }

        // Extract the stuff we need to figure out whether this guy belongs at our site
        long client_handle = request.getClientHandle();
        request.buildParameterSet();
        assert(request.getParams() != null) : "The parameters object is null for new txn from client #" + client_handle;
        Object args[] = request.getParams().toArray(); 
        // LOG.info("Parameter Size = " + request.getParameters())
        
        Procedure catalog_proc = this.catalog_db.getProcedures().get(request.getProcName());
        final boolean sysproc = catalog_proc.getSystemproc();
        if (catalog_proc == null) throw new RuntimeException("Unknown procedure '" + request.getProcName() + "'");
        if (trace) LOG.trace("Received new stored procedure invocation request for " + catalog_proc);
        
        // First figure out where this sucker needs to go
        Integer dest_partition;
        // If it's a sysproc, then it doesn't need to go to a specific partition
        if (sysproc) {
            // Just pick the first one for now
            dest_partition = CollectionUtil.getFirst(this.executors.keySet());
        // Otherwise we use the PartitionEstimator to know where it is going
        } else {
            try {
                dest_partition = this.p_estimator.getPartition(catalog_proc, args);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        // assert(dest_partition >= 0);
        if (trace) {
            LOG.trace("Client Handle = " + client_handle);
            LOG.trace("Destination Partition = " + dest_partition);
        }
        
        // TODO: If the dest_partition isn't local, then we need to ship it off to the right location
        if (this.executors.containsKey(dest_partition) == false) {
            if (trace) LOG.trace("StoredProcedureInvocation request for " +  catalog_proc + " needs to be forwarded to Partition #" + dest_partition);
            
            // Make a wrapper for the original callback so that when the result comes back frm the remote partition
            // we will just forward it back to the client. How sweet is that??
            ForwardTxnRequestCallback callback = new ForwardTxnRequestCallback(done);
            this.messenger.forwardTransaction(serializedRequest, callback, dest_partition);
            return;
        }

        // Important: We have two txn ids here. We have the real one that we're going to use internally
        // Setup "Magic Evan" RPC stuff
        long txn_id = -1;
        if (this.getSiteId() == 0) {
            txn_id = NEXT_TXN_ID.getAndIncrement();
        } else {
            txn_id = this.messenger.getNextTxnId();
        }
        assert(txn_id != -1);
        
        ProtoRpcController rpc = new ProtoRpcController();
        Dtxn.CoordinatorFragment.Builder requestBuilder = Dtxn.CoordinatorFragment.newBuilder();
        requestBuilder.setTransactionId((int)txn_id);
        requestBuilder.setLastFragment(true);
        
        // Grab the TransactionEstimator for the destination partition and figure out whether
        // this mofo is likely to be single-partition or not. Anything that we can't estimate
        // will just have to be multi-partitioned. This includes sysprocs
        ExecutionSite executor = this.executors.get(dest_partition);
        TransactionEstimator t_estimator = executor.getTransactionEstimator();
        
        Boolean single_partition = null;
        if (sysproc) {
            single_partition = false;
        } else if (!t_estimator.canEstimate(catalog_proc)) {
            single_partition = false;
        } else {
            if (trace) LOG.trace("Using TransactionEstimator to check whether txn #" + txn_id + " is single-partition");
            TransactionEstimator.Estimate estimate = t_estimator.startTransaction(txn_id, catalog_proc, args);
            single_partition = estimate.isSinglePartition(this.thresholds);
        }
        assert (single_partition != null);
        //LOG.debug("Single-Partition = " + single_partition);
        InitiateTaskMessage wrapper = new InitiateTaskMessage(txn_id, dest_partition, dest_partition, client_handle, request);
        
        if (single_partition) this.singlepart_ctr.incrementAndGet();
        else this.multipart_ctr.incrementAndGet();
        this.inflight_txns.put(txn_id, dest_partition);
        if (debug) LOG.debug("Passing " + catalog_proc.getName() + " to Coordinator as " + (single_partition ? "single" : "multi") + "-partition txn #" + txn_id + " for partition " + dest_partition);
        
        // Even though this is possibly local, we still have to go through the Dtxn.Coordinator <-- Is that true??
//        FragmentResponsePassThroughCallback rpcDone = new FragmentResponsePassThroughCallback(txn_id, t_estimator, done);
//        executor.doWork(wrapper, rpcDone);
        
        requestBuilder.addFragment(Dtxn.CoordinatorFragment.PartitionFragment.newBuilder()
                .setPartitionId(dest_partition)
                // TODO: Use copyFrom(ByteBuffer) in the newer version of protobuf
                .setWork(ByteString.copyFrom(wrapper.getBufferForMessaging(this.buffer_pool).b.array())))
                // 2010-06-18: We need to set this for single-partition txns
                .setLastFragment(single_partition);
        //CoordinatorResponsePassThroughCallback rpcDone = new CoordinatorResponsePassThroughCallback(this, txn_id, t_estimator, done);
        if (trace) LOG.trace("Passing txn #" + txn_id + " through Dtxn.Coordinator using InitiateCallback");
        
        // Create a latch so that we don't start executing until we know the coordinator confirmed are initialization request
        CountDownLatch latch = new CountDownLatch(1);
        InitiateCallback callback = new InitiateCallback(this, txn_id, t_estimator, latch);
        this.client_callbacks.put(txn_id, done);
        this.init_latches.put(txn_id, latch);
        
        this.coordinator.execute(rpc, requestBuilder.build(), callback);
    }

    /**
     * Execute some work on a particular ExecutionSite
     */
    @Override
    public void execute(RpcController controller, Dtxn.Fragment request, RpcCallback<Dtxn.FragmentResponse> done) {
        final boolean trace = LOG.isTraceEnabled();
        
        // Decode the procedure request
        TransactionInfoBaseMessage msg = null;
        try {
            msg = (TransactionInfoBaseMessage)VoltMessage.createMessageFromBuffer(request.getWork().asReadOnlyByteBuffer(), false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // IMPORTANT: Evan betrayed our love so we can't use his txn ids because they are meaningless to us
        //            Make sure that we always use the txn ids pulled from our internal messages
//        long txn_id = NEXT_TXN_ID.getAndIncrement();
        long txn_id = msg.getTxnId(); // 
        int partition = msg.getDestinationPartitionId();
        if (trace) {
            LOG.trace("Got " + msg.getClass().getSimpleName() + " message for txn #" + txn_id + " running at Partition #" + partition);
            LOG.trace("CONTENTS:\n" + msg);
        }
        ExecutionSite executor = this.executors.get(partition);
        if (executor == null) {
            throw new RuntimeException("No ExecutionSite exists for Partition #" + partition + " at this site???");
        }
        TransactionEstimator t_estimator = executor.getTransactionEstimator();
        
        // Two things can now happen based on what type of message we were given:
        //
        //  (1) If we have an InitiateTaskMessage, then this call is for starting a new txn
        //      at this site. We need to send back a placeholder response through the Dtxn.Coordinator
        //      so that we are allowed to execute queries on remote partitions later.
        //      Note that we maintain the callback to the client so that we know how to send back
        //      our ClientResponse once the txn is finished.
        //
        //  (2) Any other message can just be sent along to the ExecutionSite without sending
        //      back anything right away. The ExecutionSite will use our callback handle
        //      to send back whatever response it needs to on its own.
        RpcCallback<Dtxn.FragmentResponse> callback = null;
        if (msg instanceof InitiateTaskMessage) {
            // We need to send back a response before we actually start executing to avoid a race condition
            if (trace) LOG.trace("Sending back FragmentResponse for InitiateTaskMessage message on txn #" + txn_id);
            Dtxn.FragmentResponse response = Dtxn.FragmentResponse.newBuilder()
                                                    .setStatus(Status.OK)
                                                    .setOutput(ByteString.EMPTY)
                                                    .build();
            done.run(response);
            
            // Now wait until we know the Dtxn.Coordinator processed our request
            if (trace) LOG.trace("Waiting for Dtxn.coordinator to process our initialization response because Evan eats babies!!");
            CountDownLatch latch = this.init_latches.remove(txn_id);
            assert(latch != null) : "Missing initialization latch for txn #" + txn_id;
            try {
                latch.await();
            } catch (Exception ex) {
                LOG.fatal("Unexpected error when waiting for latch on txn #" + txn_id, ex);
                this.shutdown();
            }
            if (trace) LOG.trace("Got the all clear message for txn #" + txn_id);
            
            RpcCallback<byte[]> client_callback = this.client_callbacks.get(txn_id);
            assert(client_callback != null) : "Missing original RpcCallback for txn #" + txn_id;
            callback = new FragmentResponsePassThroughCallback(this, txn_id, t_estimator, client_callback);
        } else {
            callback = done;
        }
            
        executor.doWork(msg, callback);
    }

    @Override
    public void finish(RpcController controller, Dtxn.FinishRequest request, RpcCallback<Dtxn.FinishResponse> done) {
        final boolean trace = LOG.isTraceEnabled(); 
        long txn_id = request.getTransactionId();
        if (trace) LOG.trace("Got " + request.getClass().getSimpleName() + " for txn #" + txn_id + " [commit=" + request.getCommit() + "]");
        
        // Tell our node to either commit or abort the txn in the FinishRequest
        // FIXME: Dtxn.FinishRequest needs to tell us what partition to tell to commit/abort
        for (ExecutionSite executor : this.executors.values()) {
            if (request.getCommit()) {
                executor.commitWork(txn_id);
            } else {
                executor.abortWork(txn_id);
            }
        } // FOR
        
        // Send back a FinishResponse to let them know we're cool with everything...
        Dtxn.FinishResponse.Builder builder = Dtxn.FinishResponse.newBuilder();
        done.run(builder.build());
        if (trace) LOG.trace("Sent back FinishResponse for txn #" + txn_id);
    }
    
    /**
     * 
     * @param hstore_node
     * @param hstore_conf_path
     * @param dtxnengine_path
     * @param dtxncoordinator_path
     * @param dtxncoord_path
     * @param execHost
     * @param execPort
     * @param coordinatorHost
     * @param coordinatorPort
     * @throws Exception
     */
    public static void launch(final HStoreCoordinatorNode hstore_node,
                              final String hstore_conf_path, final String dtxnengine_path, final String dtxncoordinator_path,
                              final String coordinatorHost, final int coordinatorPort) throws Exception {
        final boolean debug = LOG.isDebugEnabled();
        List<Thread> threads = new ArrayList<Thread>();
        final Site catalog_site = hstore_node.getSite();
        final int num_partitions = catalog_site.getPartitions().size();
        final String site_host = catalog_site.getHost().getIpaddr();
        
        // ----------------------------------------------------------------------------
        // (1) ProtoServer Thread (one per site)
        // ----------------------------------------------------------------------------
        if (debug) LOG.debug(String.format("Launching ProtoServer [site=%d, port=%d]", catalog_site.getId(), catalog_site.getDtxn_port()));
        final NIOEventLoop execEventLoop = new NIOEventLoop();
        final CountDownLatch execLatch = new CountDownLatch(1);
        execEventLoop.setExitOnSigInt(true);
        threads.add(new Thread() {
            {
                this.setName(hstore_node.getThreadName("proto"));
            }
            public void run() {
                ProtoServer execServer = new ProtoServer(execEventLoop);
                execServer.register(hstore_node);
                execServer.bind(catalog_site.getDtxn_port());
                execLatch.countDown();
                execEventLoop.run();
            };
        });
        
        // ----------------------------------------------------------------------------
        // (2) DTXN Engine Threads (one per partition)
        // ----------------------------------------------------------------------------
        if (debug) LOG.debug(String.format("Launching DTXN Engine for %d partitions", num_partitions));
        final CountDownLatch dtxnExecLatch = new CountDownLatch(num_partitions);
        
        // We need one protodtxnengine per partition
        for (final Partition catalog_part : catalog_site.getPartitions()) {
            threads.add(new Thread() {
                {
                    this.setName(hstore_node.getThreadName("init"));
                }
                public void run() {
                    int partition = catalog_part.getId();
                    if (execLatch.getCount() > 0) {
                        if (debug) LOG.debug("Waiting for ProtoServer to finish start up for Partition #" + partition);
                        try {
                            execLatch.await();
                        } catch (InterruptedException ex) {
                            LOG.error(ex);
                            return;
                        }
                    }
                    
                    int port = catalog_site.getDtxn_port();
                    if (debug) LOG.debug("Forking off protodtxnengine for Partition #" + partition + " [outbound_port=" + port + "]");
                    String[] command = new String[]{
                        dtxnengine_path,                // protodtxnengine
                        site_host + ":" + port,         // host:port (ProtoServer)
                        hstore_conf_path,               // hstore.conf
                        Integer.toString(partition),    // partition #
                        "0"                             // ??
                    };
                    String ready_msg = "[ready]";
                    ThreadUtil.forkLatch(command, ready_msg, dtxnExecLatch, hstore_node.shutdown_observable);
                }
            });
        } // FOR (partition)
        
        // ----------------------------------------------------------------------------
        // (3) Procedure Request Listener Thread
        // ----------------------------------------------------------------------------
        threads.add(new Thread() {
            {
                this.setName(hstore_node.getThreadName("coord"));
            }
            public void run() {
                if (debug) LOG.debug("Creating connection to coordinator at " + coordinatorHost + ":" + coordinatorPort + " [site=" + catalog_site.getId() + "]");
                
                hstore_node.coordinatorEventLoop.setExitOnSigInt(true);
                InetSocketAddress[] addresses = {
                        new InetSocketAddress(coordinatorHost, coordinatorPort),
                };
                ProtoRpcChannel[] channels =
                    ProtoRpcChannel.connectParallel(hstore_node.coordinatorEventLoop, addresses);
                Dtxn.Coordinator stub = Dtxn.Coordinator.newStub(channels[0]);
                hstore_node.setDtxnCoordinator(stub);
                VoltProcedureListener voltListener = new VoltProcedureListener(hstore_node.coordinatorEventLoop, hstore_node);
                voltListener.bind(catalog_site.getProc_port());
                LOG.info("HStoreCoordinatorNode is ready for action [site=" + catalog_site.getId() + ", port=" + catalog_site.getProc_port() + "]");
                
                boolean shutdown = false;
                Exception error = null;
                try {
                    hstore_node.coordinatorEventLoop.run();
                } catch (AssertionError ex) {
                    LOG.fatal("Coordinator thread failed", ex);
                    error = new Exception(ex);
                    shutdown = true;
                } catch (Exception ex) {
                    LOG.fatal("Coordinator thread failed", ex);
                    error = ex;
                    shutdown = true;
                }
                if (shutdown && hstore_node.shutdown == false) hstore_node.messenger.shutdownCluster(error);
            };
        });
        
        // Set them all to be daemon threads
        for (Thread t : threads) {
            t.setDaemon(true);
        }

        // Blocks!
        ThreadUtil.run(threads);
    }
    
    /**
     * Required Arguments
     * catalog.jar=<path/to/catalog.jar>
     * coordinator.host=<hostname>
     * coordinator.port=<#>
     * 
     * @param vargs
     * @throws Exception
     */
    public static void main(String[] vargs) throws Exception {
        ArgumentsParser args = ArgumentsParser.load(vargs);
        args.require(ArgumentsParser.PARAM_CATALOG,
                     ArgumentsParser.PARAM_COORDINATOR_HOST,
                     ArgumentsParser.PARAM_COORDINATOR_PORT,
                     ArgumentsParser.PARAM_NODE_SITE,
                     ArgumentsParser.PARAM_DTXN_CONF,
                     ArgumentsParser.PARAM_DTXN_ENGINE
        );

        // HStoreNode Stuff
        final int site_id = args.getIntParam(ArgumentsParser.PARAM_NODE_SITE);
        Thread t = Thread.currentThread();
        t.setName(String.format("H%03d-main", site_id));

        // HStoreCoordinator Stuff
        final String coordinatorHost = args.getParam(ArgumentsParser.PARAM_COORDINATOR_HOST);
        final int coordinatorPort = args.getIntParam(ArgumentsParser.PARAM_COORDINATOR_PORT);

        // For every partition in our local site, we want to setup a new ExecutionSite
        // Thankfully I had enough sense to have PartitionEstimator take in the local partition
        // as a parameter, so we can share a single instance across all ExecutionSites
        Site catalog_site = CatalogUtil.getSiteFromId(args.catalog_db, site_id);
        PartitionEstimator p_estimator = new PartitionEstimator(args.catalog_db, args.hasher);
        Map<Integer, ExecutionSite> executors = new HashMap<Integer, ExecutionSite>();

        // MarkovGraphs
        Map<Integer, Map<Procedure, MarkovGraph>> markovs = null;
        if (args.hasParam(ArgumentsParser.PARAM_MARKOV)) {
            File path = new File(args.getParam(ArgumentsParser.PARAM_MARKOV));
            if (path.exists()) {
                markovs = MarkovUtil.load(args.catalog_db, path.getAbsolutePath(), CatalogUtil.getAllPartitionIds(args.catalog_db));
            } else {
                LOG.warn("The Markov Graphs file '" + path + "' does not exist");
            }
        }

        // Workload Trace Output
        if (args.hasParam(ArgumentsParser.PARAM_WORKLOAD_OUTPUT)) {
            ProcedureProfiler.profilingLevel = ProcedureProfiler.Level.INTRUSIVE;
            String traceClass = WorkloadTraceFileOutput.class.getName();
            String tracePath = args.getParam(ArgumentsParser.PARAM_WORKLOAD_OUTPUT) + ".hstorenode-" + site_id;
            String traceIgnore = args.getParam(ArgumentsParser.PARAM_WORKLOAD_PROC_EXCLUDE);
            ProcedureProfiler.initializeWorkloadTrace(args.catalog, traceClass, tracePath, traceIgnore);
            
            // For each HStoreCoordinatorNode, we need to make sure that the trace ids start at our offset
            // This will allow us to merge multiple traces together for a benchmark single-run
            long start_id = (site_id + 1) * 100000l;
            AbstractTraceElement.setStartingId(start_id);
            
            LOG.info("Enabled workload logging '" + tracePath + "' with trace id offset " + start_id);
        }
        
        // Partition Initialization
        for (Partition catalog_part : catalog_site.getPartitions()) {
            int local_partition = catalog_part.getId();

            // Initialize TransactionEstimator stuff
            // Load the Markov models if we were given an input path and pass them to t_estimator
            // HACK: For now we have to create a TransactionEstimator for all partitions, since
            // it is written under the assumption that it was going to be running at just a single partition
            // I'm not proud of this...
            // Load in all the partition-specific TransactionEstimators and ExecutionSites in order to 
            // stick them into the HStoreCoordinator
            LOG.debug("Creating Estimator for Site #" + site_id);
            TransactionEstimator t_estimator = new TransactionEstimator(local_partition, p_estimator, args.param_correlations);
            if (markovs != null) {
                t_estimator.addMarkovGraphs(markovs.get(local_partition));
            }

            // setup the EE
            LOG.debug("Creating ExecutionSite for Partition #" + local_partition);
            ExecutionSite executor = new ExecutionSite(
                    local_partition,
                    args.catalog,
                    BackendTarget.NATIVE_EE_JNI, // BackendTarget.NULL,
                    p_estimator,
                    t_estimator);
            executors.put(local_partition, executor);
        } // FOR
        
        // Now we need to create an HStoreMessenger and pass it to all of our ExecutionSites
            
        HStoreCoordinatorNode node = new HStoreCoordinatorNode(catalog_site, executors, p_estimator);
        node.setEstimationThresholds(args.thresholds); // may be null...
        
        // Status Monitor
        if (args.hasParam(ArgumentsParser.PARAM_COORDINATOR_STATUS_INTERVAL)) {
            int interval = args.getIntParam(ArgumentsParser.PARAM_COORDINATOR_STATUS_INTERVAL);
            // assert(interval > 0) : "Invalid value '" + interval + "' for parameter " + ArgumentsParser.PARAM_COORDINATOR_STATUS_INTERVAL; 
            node.enableStatusMonitor(interval);
        }
        
        // Bombs Away!
        LOG.debug("Instantiating HStoreCoordinator network connections...");
        HStoreCoordinatorNode.launch(node,
                args.getParam(ArgumentsParser.PARAM_DTXN_CONF), 
                args.getParam(ArgumentsParser.PARAM_DTXN_ENGINE),
                args.getParam(ArgumentsParser.PARAM_DTXN_COORDINATOR),
                coordinatorHost, coordinatorPort);
    }
}
