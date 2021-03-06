/*
 * Copyright (C) 2015, 2016 RAPID EU Project
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 *******************************************************************************/
package eu.project.rapid.ac;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.AsyncTask;
import android.util.Log;
import android.util.SparseArray;

import com.nabinbhandari.android.permissions.PermissionHandler;
import com.nabinbhandari.android.permissions.Permissions;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import eu.project.rapid.ac.d2d.PhoneSpecs;
import eu.project.rapid.ac.db.DBCache;
import eu.project.rapid.ac.profilers.DeviceProfiler;
import eu.project.rapid.ac.profilers.NetworkProfiler;
import eu.project.rapid.ac.profilers.Profiler;
import eu.project.rapid.ac.profilers.ProgramProfiler;
import eu.project.rapid.ac.utils.Constants;
import eu.project.rapid.common.AnimationMsgSender;
import eu.project.rapid.common.Clone;
import eu.project.rapid.common.Configuration;
import eu.project.rapid.common.RapidConstants;
import eu.project.rapid.common.RapidConstants.COMM_TYPE;
import eu.project.rapid.common.RapidConstants.ExecLocation;
import eu.project.rapid.common.RapidMessages;
import eu.project.rapid.common.RapidMessages.AnimationMsg;
import eu.project.rapid.common.RapidUtils;

import static eu.project.rapid.ac.profilers.Profiler.REGIME_CLIENT;
import static eu.project.rapid.ac.profilers.Profiler.REGIME_SERVER;

/**
 * The most important class of the framework for the client program - controls DSE, profilers,
 * communicates with remote server.
 */

public class DFE {

    // The only instance of the DFE.
    // Ignore the warning for now, also Android implements Singleton with reference to context
    // in the same way: https://developer.android.com/training/volley/requestqueue.html#singleton
    private static volatile DFE instance = null;

    private static final String TAG = "DFE";
    private Configuration config;

    private static volatile int mRegime;
    private static COMM_TYPE commType = COMM_TYPE.SSL;
    private ExecLocation userChoice = ExecLocation.DYNAMIC;

    private long mPureRemoteDuration;
    private long prepareDataDuration;

    private String mAppName;
    private Context mContext;
    private PackageManager mPManager;
    private static boolean isDFEActive = false;
    private int nrClones;
    private DSE mDSE;
    private NetworkProfiler netProfiler;

    private static Clone sClone;
    private PhoneSpecs myPhoneSpecs;

    private static ScheduledThreadPoolExecutor d2dSetReaderThread;

    private static ExecutorService threadPool;
    private static TreeSet<PhoneSpecs> d2dSetPhones = new TreeSet<>();
    private static BlockingDeque<Task> tasks = new LinkedBlockingDeque<>();
    private static AtomicInteger taskId = new AtomicInteger();
    private static SparseArray<BlockingDeque<Object>> tasksResultsMap = new SparseArray<>();
    private static CountDownLatch waitForTaskRunners;
    private static final int nrTaskRunners = 3;
    private static List<TaskRunner> taskRunnersList;

    public static final boolean useAnimationServer = true;
    private static final AnimationMsgSender animationMsgSender =
            AnimationMsgSender.getInstance(RapidConstants.DEFAULT_SERVER_IP,
                    RapidConstants.DEFAULT_PRIMARY_ANIMATION_SERVER_PORT);

    // Get broadcast messages from the Rapid service. They will contain network measurements etc.
    private static BroadcastReceiver rapidBroadcastReceiver;

    private ProgressDialog pd = null;

    /**
     * Interface to be implemented by some class that wants to be updated about some events.
     *
     * @author sokol
     */
    public interface DfeCallback {
        // Send updates about the VM connection status.

        /**
         * get formatted name based on {@link }
         **/
        void vmConnectionStatusUpdate(boolean isConnected, COMM_TYPE commType);
    }

    /**
     * Create DFE which decides where to execute remoteable methods and handles execution.
     *
     * @param clone    The clone to connect with.<br>
     *                 If null then the DFE will connect to the manager and will ask for the clone.
     * @param appName  Application name, usually the result of getPackageName().
     * @param pManager Package manager for finding apk file of the application.
     * @param context  Is the context of the application, to be passed by the activity that will create the DFE.
     */
    private DFE(String appName, PackageManager pManager, Context context, Clone clone) {

        Log.d(TAG, "DFE Created");
        isDFEActive = true;
        sClone = clone;
        DFE.mRegime = REGIME_CLIENT;
        this.mAppName = appName;
        this.mPManager = pManager;
        this.mContext = context;
        this.myPhoneSpecs = PhoneSpecs.getPhoneSpecs(mContext);
        d2dSetReaderThread = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
        threadPool = Executors.newFixedThreadPool(nrTaskRunners);
        waitForTaskRunners = new CountDownLatch(nrTaskRunners);
        taskRunnersList = new LinkedList<>();

        rapidBroadcastReceiver = new RapidBroadcastReceiver();
        IntentFilter filter = new IntentFilter(RapidNetworkService.RAPID_NETWORK_CHANGED);
        filter.addAction(RapidNetworkService.RAPID_VM_CHANGED);
        filter.addAction(RapidNetworkService.RAPID_D2D_SET_CHANGED);
        mContext.registerReceiver(rapidBroadcastReceiver, filter);

        Log.i(TAG, "Current device: " + myPhoneSpecs);

        Log.i(TAG, "Trying to get the runtime WRITE_EXTERNAL_STORAGE permission...");
        Permissions.check(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                "I need to write log files on the sdcard",
                new PermissionHandler() {
                    @Override
                    public void onGranted() {
                        Log.i(TAG, "WRITE_EXTERNAL_STORAGE permission granted.");
                        createRapidFoldersIfNotExist();
                        readConfigurationFile();
                        initializeCrypto();
                    }
                });

        mDSE = DSE.getInstance(this.mAppName);

        if(config == null)
            config = new Configuration();

        config.setClone(sClone);
        netProfiler = new NetworkProfiler(mContext, config);
        start();
    }

    private void start() {
        netProfiler.registerNetworkStateTrackers();

        // Start the service that will deal with the Rapid registration and D2D communication
        if (sClone == null) {
            startRapidService();
        }

        // Show a spinning dialog while connecting to the Manager and to the clone.
        this.pd = ProgressDialog.show(mContext, "Working...", "Initial network tasks...", true, false);
        (new InitialNetworkTasks()).execute(sClone);
    }

    /**
     * To be used on server side, only local execution
     */
    @SuppressWarnings("unused")
    private DFE() {
        DFE.mRegime = REGIME_SERVER;
    }

    /**
     * The method to be used by applications.
     *
     * @return The only instance of the DFE
     */
    public static DFE getInstance(String appName, PackageManager pManager, Context context) {
        return getInstance(appName, pManager, context, null, COMM_TYPE.SSL);
    }

    /**
     * The method used for testing.
     *
     * @return The only instance of the DFE
     */
    public static DFE getInstance(String appName, PackageManager pManager, Context context,
                                  Clone clone, COMM_TYPE commType) {
        // local variable increases performance by 25 percent according to
        // Joshua Bloch "Effective Java, Second Edition", p. 283-284
        DFE result = instance;
        Log.e("LOLOMO", clone.toString());
        synchronized (DFE.class) {
            if (result == null) {
                result = instance;
                if (result == null) {
                    DFE.commType = commType;
                    instance = result = new DFE(appName, pManager, context, clone);
                }
            }
        }

        return result;
    }

    private void createRapidFoldersIfNotExist() {
        File rapidDir = new File(Constants.RAPID_FOLDER);
        if (!rapidDir.exists()) {
            if (!rapidDir.mkdir()) {
                Log.w(TAG, "Could not create the RAPID folder: " + Constants.RAPID_FOLDER);
            }
        }

        File rapidTestDir = new File(Constants.TEST_LOGS_FOLDER);
        if (!rapidTestDir.exists()) {
            if (!rapidTestDir.mkdirs()) {
                Log.w(TAG, "Could not create the RAPID test folder: " + Constants.TEST_LOGS_FOLDER);
            }
        }
    }

    private void readConfigurationFile() {
        try {
            // Read the config file to read the IP and port of Manager
            config = new Configuration(Constants.PHONE_CONFIG_FILE);
            config.parseConfigFile();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Config file not found: " + Constants.PHONE_CONFIG_FILE);
            config = new Configuration();
        }
    }

    /**
     * Create the necessary stuff for the SSL connection.
     */
    private void initializeCrypto() {
        try {
            Log.i(TAG, "Started reading the cryptographic keys");

            // Read the CA certificate
            KeyStore trustStore = KeyStore.getInstance("BKS");
            trustStore.load(mContext.getAssets().open(Constants.SSL_CA_TRUSTSTORE),
                    Constants.SSL_DEFAULT_PASSW.toCharArray());
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);

            // Read my certificate
            KeyStore keyStore = KeyStore.getInstance("BKS");
            keyStore.load(mContext.getAssets().open(Constants.SSL_KEYSTORE),
                    Constants.SSL_DEFAULT_PASSW.toCharArray());
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, Constants.SSL_DEFAULT_PASSW.toCharArray());

            PrivateKey myPrivateKey = (PrivateKey) keyStore.getKey(Constants.SSL_CERT_ALIAS,
                    Constants.SSL_DEFAULT_PASSW.toCharArray());
            Certificate cert = keyStore.getCertificate(Constants.SSL_CERT_ALIAS);
            PublicKey myPublicKey = cert.getPublicKey();
            Log.i(TAG, "Certificate: " + cert.toString());
            Log.i(TAG, "PrivateKey algorithm: " + myPrivateKey.getAlgorithm());
            Log.i(TAG, "PublicKey algorithm: " + myPrivateKey.getAlgorithm());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            SSLSocketFactory sslFactory = sslContext.getSocketFactory();
            Log.i(TAG, "SSL Factory created");

            config.setCryptoInitialized(true);
            config.setPrivateKey(myPrivateKey);
            config.setPublicKey(myPublicKey);
            config.setSslFactory(sslFactory);
            config.setSslContext(sslContext);

        } catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException
                | UnrecoverableKeyException | CertificateException | IOException e) {
            Log.e(TAG, "Could not initialize the crypto parameters - " + e);
        }
    }

    private class InitialNetworkTasks extends AsyncTask<Clone, String, Void> {

        private static final String TAG = "InitialNetworkTasks";

        @Override
        protected Void doInBackground(Clone... clone) {
            if (clone[0] == null) {

                publishProgress("Getting info from the AC_RM...");
                // Just try to connect once with the ac_rm. If the connection fails,
                // meaning that the ac_rm is not ready yet,
                // the ac_rm will notify us by broadcast intents when something happens.
                int maxNrTimes = 1;
                int count = 0;
                boolean connectedWithAcRm = false;
                Socket acRmSocket = null;

                do {
                    Log.v(TAG, "Connecting with AC_RM...");

                    try {
                        acRmSocket = new Socket();
                        acRmSocket.connect(new InetSocketAddress(InetAddress.getLocalHost(),
                                RapidNetworkService.AC_RM_PORT), 1000);
                        connectedWithAcRm = true;
                        Log.i(TAG, "Connected with AC_RM!");
                    } catch (IOException e) {
                        Log.e(TAG, "Could not connect with AC_RM: " + e);
                    } finally {
                        count++;
                    }
                } while (!connectedWithAcRm && count < maxNrTimes);

                if (connectedWithAcRm) {
                    Log.i(TAG, "Connection with AC_RM was successful, getting the needed info...");
                    try (OutputStream os = acRmSocket.getOutputStream();
                         InputStream is = acRmSocket.getInputStream();
                         ObjectOutputStream oos = new ObjectOutputStream(os);
                         ObjectInputStream ois = new ObjectInputStream(is)) {

                        os.write(RapidNetworkService.AC_GET_VM);
                        sClone = (Clone) ois.readObject();
                        config.setClone(sClone);

                        os.write(RapidNetworkService.AC_GET_NETWORK_MEASUREMENTS);
                        NetworkProfiler.setUlRate(ois.readInt());
                        NetworkProfiler.setDlRate(ois.readInt());
                        NetworkProfiler.setRtt(ois.readInt());
                    } catch (IOException | ClassNotFoundException e) {
                        Log.e(TAG, "AC_RM could not provide the needed info: " + e);
                    }
                }
            } else {
                publishProgress("Using the VM given by the user: " + clone[0]);
                sClone = clone[0];
                config.setClone(sClone);
                NetworkProfiler.measureUlRate(sClone.getIp(), sClone.getClonePortBandwidthTest());
                NetworkProfiler.measureDlRate(sClone.getIp(), sClone.getClonePortBandwidthTest());
                NetworkProfiler.measureRtt(sClone.getIp(), sClone.getClonePortBandwidthTest());
            }

            publishProgress("Starting the TaskRunner threads...");
            // Start TaskRunner threads that will handle the task dispatching process.
            for (int i = 0; i < nrTaskRunners; i++) {
                TaskRunner t = new TaskRunner(i);
                taskRunnersList.add(t);
                threadPool.submit(t);
            }
            try {
                waitForTaskRunners.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return null;
        }

        @Override
        protected void onProgressUpdate(String... progress) {
            Log.i(TAG, progress[0]);
            if (pd != null) {
                pd.setMessage(progress[0]);
            }
        }

        @Override
        protected void onPostExecute(Void result) {
            Log.i(TAG, "Finished initial network tasks");
            if (pd != null) {
                pd.dismiss();
            }
        }

        @Override
        protected void onPreExecute() {
            Log.i(TAG, "Started initial network tasks");
        }
    }

    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        isDFEActive = false;
        DeviceProfiler.onDestroy();
        netProfiler.onDestroy();
        DBCache.saveDbCache();
        threadPool.shutdownNow();
        d2dSetReaderThread.shutdown();
        instance = null;
        if (rapidBroadcastReceiver != null) {
            mContext.unregisterReceiver(rapidBroadcastReceiver);
        }
        // FIXME Should I also stop the D2D listening service here or should I leave it running?
    }

    private void startRapidService() {
        Log.i(TAG, "Starting the RAPID listening service...");
        Intent rapidServiceIntent = new Intent(mContext, RapidNetworkService.class);
        mContext.startService(rapidServiceIntent);
    }

    /**
     * Wrapper of the execute method with no parameters for the executable method
     *
     * @param m The method to be executed.
     * @param o The object calling the method.
     * @return The result of the method execution, which can also be an exception if something went wrong.
     * @throws Throwable
     */
    public Object execute(Method m, Object o) throws Throwable {
        return execute(m, null, o);
    }

    /**
     * Call DSE to decide where to execute the operation, start profilers, execute (either locally or
     * remotely), collect profiling data and return execution results.
     *
     * @param m       method to be executed
     * @param pValues with parameter values
     * @param o       on object
     * @return result of execution, or an exception if it happened
     * @throws NoSuchMethodException    If the method was not found in the object class.
     * @throws ClassNotFoundException   If the class of the object could not be found by the classloader.
     * @throws IllegalAccessException
     * @throws SecurityException
     * @throws IllegalArgumentException If the arguments passed to the method are not correct.
     */
    public Object execute(Method m, Object[] pValues, Object o) throws IllegalArgumentException,
            SecurityException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException {

        Object result = null;
        try {
            int id = taskId.incrementAndGet();
            tasksResultsMap.put(id, new LinkedBlockingDeque<>());
            Log.v(TAG, "Adding task on the tasks blocking queue...");
            tasks.put(new Task(id, m, pValues, o));
            Log.v(TAG, "Task added");

            Log.v(TAG, "Waiting for the result of this task to be inserted in the queue by the working thread...");
            result = tasksResultsMap.get(id).take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result;
    }

    private class Task {
        Method m;
        Object[] pValues;
        Object o;
        int id;

        Task(int id, Method m, Object[] pValues, Object o) {
            this.id = id;
            this.m = m;
            this.pValues = pValues;
            this.o = o;
        }
    }

    private class TaskRunner implements Runnable {
        private final String TAG;
        private boolean onLineClear = false;
        private boolean onLineSSL = false;
        private Socket s;
        private InputStream is;
        private OutputStream os;
        private ObjectInputStream ois;
        private ObjectOutputStream oos;
        private int id;

        ScheduledThreadPoolExecutor vmConnectionScheduledPool =
                (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
        // Every two minutes check if we need to reconnect to the VM
        static final int FREQUENCY_VM_CONNECTION = 2 * 60 * 1000;

        TaskRunner(int id) {
            this.id = id;
            TAG = "DFE-TaskRunner-" + id;
        }

        @Override
        public void run() {

            registerWithVm();
            // Schedule some periodic reconnections with the VM, just in case we lost the connection.
            vmConnectionScheduledPool.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            registerWithVm();
                        }
                    }, FREQUENCY_VM_CONNECTION, FREQUENCY_VM_CONNECTION, TimeUnit.MILLISECONDS
            );

            waitForTaskRunners.countDown();

            while (true) {
                try {
                    Log.v(TAG, "Waiting for task...");
                    Task task = tasks.take();
                    Log.v(TAG, "Got a task, executing...");
                    Object result = runTask(task, os, ois, oos);
                    Log.v(TAG, "Task finished execution, putting result on the resultMap...");
                    // If the method returned void then the result may be null and here we get
                    // NullPointerException then. In that case insert an empty Object as result.
                    tasksResultsMap.get(task.id).put(result != null ? result : new Object());
                    Log.v(TAG, "Result inserted on the resultMap.");
                } catch (InterruptedException e) {
                    if (!isDFEActive) {
                        Log.v(TAG, "The DFE is destroyed, exiting");
                        closeConnection();
                        vmConnectionScheduledPool.shutdownNow();
                        break;
                    } else {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception on TaskRunner: " + e);
                    e.printStackTrace();
                }
            }

            Log.v(TAG, "Thread exited");
        }

        private void registerWithVm() {
            Log.d(TAG, "Registering with the VM...");

            if (onLineClear || onLineSSL) {
                Log.v(TAG, "We are already connected to the VM, no need for reconnection.");
                return;
            }

            if (sClone == null) {
                Log.v(TAG, "The VM is null, aborting VM registration.");
                return;
            }

            // In case of reconnecting with the VM, always try to do that using SSL, if possible.
            if (config.isCryptoInitialized()) {
                commType = COMM_TYPE.SSL;
            }

            if (useAnimationServer && this.id == 0) {
                if (RapidNetworkService.usePrevVm) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_REGISTER_VM);
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_CONN_VM);
                } else {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_REGISTER_VM);
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_CONN_VM);
                }
            }

            if (commType == COMM_TYPE.CLEAR) {
                establishClearConnection();
            } else { // (commType == COMM_TYPE.SSL)
                if (!establishSslConnection()) {
                    Log.w(TAG, "Setting commType to CLEAR");
                    commType = COMM_TYPE.CLEAR;
                    establishClearConnection();
                }
            }

            // If the connection was successful then try to send the app to the clone
            if (onLineClear || onLineSSL) {

                if (useAnimationServer && this.id == 0) {
                    if (onLineSSL) animationMsgSender.sendAnimationMsg(AnimationMsg.AC_COMM_SSL);
                    else  animationMsgSender.sendAnimationMsg(AnimationMsg.AC_COMM_CLEAR);
                }

                Log.i(TAG, "The communication type established with the clone is: " + commType);
                sendApk(is, os, oos);

                if (useAnimationServer && this.id == 0) {
                    if (RapidNetworkService.usePrevVm) {
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_RTT_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_DL_RATE_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_UL_RATE_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_REGISTRATION_OK_VM);
                    } else {
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_RTT_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_DL_RATE_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_UL_RATE_VM);
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_REGISTRATION_OK_VM);
                    }
                }

            } else {
                if (useAnimationServer && this.id == 0) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_COMM_NONE);

                    if (RapidNetworkService.usePrevVm) {
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_REGISTRATION_FAIL_VM);
                    } else {
                        animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_REGISTRATION_FAIL_VM);
                    }
                }

                Log.e(TAG, "Could not register with the VM");
            }
            sendUpdateConnectionInfo();
        }

        /**
         * Send update on the connection status to the client using the framework.
         */
        private void sendUpdateConnectionInfo() {
            try {
                ((DfeCallback) mContext).vmConnectionStatusUpdate(onLineClear || onLineSSL, commType);
            } catch (ClassCastException e) {
                Log.i(TAG, "This class doesn't implement callback methods.");
            }
        }

        /**
         * Set up streams for the socket connection, perform initial communication with the clone.
         */
        private boolean establishClearConnection() {
            try {
                long sTime = System.nanoTime();
                long startTxBytes = NetworkProfiler.getProcessTxBytes();
                long startRxBytes = NetworkProfiler.getProcessRxBytes();

                Log.i(TAG, "Connecting in CLEAR with AS on: " + sClone.getIp() + ":" + sClone.getPort());
                s = new Socket();
                s.connect(new InetSocketAddress(sClone.getIp(), sClone.getPort()), 5 * 1000);

                os = s.getOutputStream();
                is = s.getInputStream();
                oos = new ObjectOutputStream(os);
                ois = new ObjectInputStream(is);

                long dur = System.nanoTime() - sTime;
                long totalTxBytes = NetworkProfiler.getProcessTxBytes() - startTxBytes;
                long totalRxBytes = NetworkProfiler.getProcessRxBytes() - startRxBytes;

                Log.d(TAG, "Socket and streams set-up time - " + dur / 1000000 + "ms");
                Log.d(TAG, "Total bytes sent: " + totalTxBytes);
                Log.d(TAG, "Total bytes received: " + totalRxBytes);
                return onLineClear = true;

            } catch (Exception e) {
                fallBackToLocalExecution("Connection setup with the VM failed - " + e);
            } finally {
                onLineSSL = false;
            }
            return onLineClear = false;
        }

        /**
         * Set up streams for the secure socket connection, perform initial communication with the server.
         */
        private boolean establishSslConnection() {

            if (!config.isCryptoInitialized()) {
                Log.e(TAG, "Crypto keys not loaded, cannot perform SSL connection!");
                return false;
            }

            try {
                Long sTime = System.nanoTime();
                long startTxBytes = NetworkProfiler.getProcessTxBytes();
                long startRxBytes = NetworkProfiler.getProcessRxBytes();

                Log.i(TAG, "Connecting in SSL with clone: " + sClone.getIp() + ":" + sClone.getSslPort());

                s = config.getSslFactory().createSocket(); //.createSocket(sClone.getIp(), sClone.getSslPort());
                s.connect(new InetSocketAddress(sClone.getIp(), sClone.getSslPort()), 5 * 1000);

                // Log.i(TAG, "getEnableSessionCreation: " + ((SSLSocket)
                // sSocket).getEnableSessionCreation());
                // ((SSLSocket) sSocket).setEnableSessionCreation(false);

                // sslContext.getClientSessionContext().getSession(null).invalidate();

                ((SSLSocket) s).addHandshakeCompletedListener(new SSLHandshakeCompletedListener());
                Log.i(TAG, "socket created");

                // Log.i(TAG, "Enabled cipher suites: ");
                // for (String s : ((SSLSocket) sSocket).getEnabledCipherSuites()) {
                // Log.i(TAG, s);
                // }

                os = s.getOutputStream();
                is = s.getInputStream();
                oos = new ObjectOutputStream(os);
                ois = new ObjectInputStream(is);

                long dur = System.nanoTime() - sTime;
                long totalTxBytes = NetworkProfiler.getProcessTxBytes() - startTxBytes;
                long totalRxBytes = NetworkProfiler.getProcessRxBytes() - startRxBytes;

                Log.d(TAG, "Socket and streams set-up time - " + dur / 1000000 + "ms");
                Log.d(TAG, "Total bytes sent: " + totalTxBytes);
                Log.d(TAG, "Total bytes received: " + totalRxBytes);
                return onLineSSL = true;

            } catch (UnknownHostException e) {
                fallBackToLocalExecution("UnknownHostException - SSL Connection setup to server failed: " + e);
            } catch (IOException e) {
                fallBackToLocalExecution("IOException - SSL Connection setup to server failed: " + e);
            } catch (Exception e) {
                fallBackToLocalExecution("Exception - SSL Connection setup to server failed: " + e);
            } finally {
                onLineClear = false;
            }

            return onLineSSL = false;
        }

        private class SSLHandshakeCompletedListener implements HandshakeCompletedListener {

            @Override
            public void handshakeCompleted(HandshakeCompletedEvent event) {

                try {
                    Log.i(TAG, "SSL handshake completed");
                    // Log.i(TAG, "getCipherSuite: " + event.getCipherSuite());
                    // Log.i(TAG, "algorithm: " + config.getPrivateKey().getAlgorithm());
                    // Log.i(TAG, "modulusBitLength: " + ((RSAPrivateKey)
                    // config.getPrivateKey()).getModulus().bitLength());

                    // SSLSession session = event.getSession();
                    // Log.i(TAG, "getProtocol: " + session.getProtocol());
                    // Log.i(TAG, "getPeerHost: " + session.getPeerHost());
                    // Log.i(TAG, "getId: " + RapidUtils.bytesToHex(session.getId()));
                    // Log.i(TAG, "getCreationTime: " + session.getCreationTime());

                    // java.security.cert.Certificate[] certs = event.getPeerCertificates();
                    // for (int i = 0; i < certs.length; i++)
                    // {
                    // if (!(certs[i] instanceof java.security.cert.X509Certificate)) continue;
                    // java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) certs[i];
                    // Log.i(TAG, "Cert #" + i + ": " + cert.getSubjectDN().getName());
                    // }
                } catch (Exception e) {
                    Log.e(TAG, "SSL handshake completed with errors: " + e);
                }
            }
        }

        private void closeConnection() {
            RapidUtils.closeQuietly(oos);
            RapidUtils.closeQuietly(ois);
            RapidUtils.closeQuietly(s);
            onLineClear = onLineSSL = false;
            sendUpdateConnectionInfo();
        }

        /**
         * Send APK file to the remote server
         */
        private void sendApk(InputStream is, OutputStream os, ObjectOutputStream oos) {

            if (useAnimationServer && this.id == 0) {
                if (RapidNetworkService.usePrevVm) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREV_APK_VM);
                } else {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_NEW_APK_VM);
                }
            }

            try {
                Log.d(TAG, "Getting apk data");
                String apkName = mPManager.getApplicationInfo(mAppName, 0).sourceDir;
                File apkFile = new File(apkName);
                Log.d(TAG, "Apk name - " + apkName);

                os.write(RapidMessages.AC_REGISTER_AS);
                // Send apkName and apkLength to the VM.
                // The clone will compare these information with what he has and tell
                // if he doesn't have the apk or this one differs in size.
                oos.writeObject(mAppName);
                oos.writeInt((int) apkFile.length());
                oos.flush();
                int response = is.read();

                if (response == RapidMessages.AS_APP_REQ_AC) {
                    // Send the APK file if needed
                    FileInputStream fin = new FileInputStream(apkFile);
                    BufferedInputStream bis = new BufferedInputStream(fin);

                    // Send the file
                    Log.d(TAG, "Sending apk");
                    byte[] tempArray = new byte[Constants.BUFFER_SIZE_APK];
                    int read;
                    while ((read = bis.read(tempArray, 0, tempArray.length)) > -1) {
                        os.write(tempArray, 0, read);
//                        oos.write(tempArray, 0, read);
                        // Log.d(TAG, "Sent " + totalRead + " of " + apkFile.length() + " bytes");
                    }
//                    oos.flush();
                    RapidUtils.closeQuietly(bis);
                }
            } catch (IOException e) {
                fallBackToLocalExecution("IOException: " + e.getMessage());
            } catch (NameNotFoundException e) {
                fallBackToLocalExecution("Application not found: " + e.getMessage());
            } catch (Exception e) {
                fallBackToLocalExecution("Exception: " + e.getMessage());
            }
        }

        private void fallBackToLocalExecution(String message) {
            Log.e(TAG, message);
            onLineClear = onLineSSL = false;
        }

        private Object runTask(Task task, OutputStream os, ObjectInputStream ois, ObjectOutputStream oos) {

            Object result = null;
            ExecLocation execLocation = findExecLocation(task.m.getName());
            if (execLocation.equals(ExecLocation.LOCAL)) {
                Log.v(TAG, "Should run the method locally...");
                // First try to see if we can offload this task to a more powerful device that is in D2D
                // distance.
                // Do this only if we are not connected to a clone, otherwise it becomes a mess.
                if (!onLineClear && !onLineSSL) {
                    if (d2dSetPhones != null && d2dSetPhones.size() > 0) {
                        Log.v(TAG, "Running the method using D2D...");
                        Iterator<PhoneSpecs> it = d2dSetPhones.iterator();
                        // This is the best phone from the D2D ones since the set is sorted and this is the
                        // first element.
                        PhoneSpecs otherPhone = it.next();
                        if (otherPhone.compareTo(myPhoneSpecs) > 0) {
                            result = executeD2D(task, otherPhone);
                        }
                    }
                }

                // If the D2D execution didn't take place or something happened that the execution was
                // interrupted the result would still be null.
                if (result == null) {
                    Log.v(TAG, "No D2D execution was performed, running locally...");
                    result = executeLocally(task);
                }
            } else if (execLocation.equals(ExecLocation.REMOTE)) {
                Log.v(TAG, "Should run the method remotely...");
                result = executeRemotely(task, os, ois, oos);
                if (result instanceof InvocationTargetException || result instanceof Exception) {
                    // The remote execution throwed an exception, try to run the method locally.
                    Log.w(TAG, "The result was InvocationTargetException. Running the method locally...");
                    result = executeLocally(task);
                }
            }

            return result;
        }

        /**
         * @param appName    The application name.
         * @param methodName The current method that we want to offload from this application.<br>
         *                   Different methods of the same application will have a different set of parameters.
         * @return The execution location which can be one of: LOCAL, REMOTE.<br>
         */
        private ExecLocation findExecLocation(String appName, String methodName) {
            Log.v(TAG, "Finding exec location for user choice: " + userChoice +
                    ", online: " + (onLineClear || onLineSSL));
            if ((onLineClear || onLineSSL)) {
                if (userChoice == ExecLocation.DYNAMIC) {
                    return mDSE.findExecLocation(appName, methodName);
                } else {
                    return userChoice;
                }
            }
            return ExecLocation.LOCAL;
        }

        /**
         * Ask the DSE to find the best offloading scheme based on user choice. Developer can use this
         * method in order to prepare the data input based on the decision the framework will make about
         * the execution location.
         *
         * @param methodName The name of the method that will be executed.
         * @return The execution location which can be one of: LOCAL, REMOTE.<br>
         */
        private ExecLocation findExecLocation(String methodName) {
            return findExecLocation(mAppName, methodName);
        }

        /**
         * Execute the method locally
         *
         * @return result of execution, or an exception if it happened
         * @throws IllegalAccessException
         * @throws SecurityException
         * @throws IllegalArgumentException If the arguments passed to the method are not correct.
         */
        private Object executeLocally(Task task) {

            ProgramProfiler progProfiler = new ProgramProfiler(mAppName, task.m.getName());
            DeviceProfiler devProfiler = new DeviceProfiler(mContext);
            Profiler profiler = new Profiler(mRegime, progProfiler, null, devProfiler);

            if (useAnimationServer)
                animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREPARE_DATA);

            // Start tracking execution statistics for the method
            profiler.startExecutionInfoTracking();
            Object result = null; // Access it
            try {
                // Make sure that the method is accessible
                if (useAnimationServer) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_DECISION_LOCAL);
                }
                // RapidUtils.sendAnimationMsg(config, RapidMessages.AC_EXEC_LOCAL);
                Long startTime = System.nanoTime();
                task.m.setAccessible(true);

                result = task.m.invoke(task.o, task.pValues);
                long mPureLocalDuration = System.nanoTime() - startTime;
                Log.d(TAG, "LOCAL " + task.m.getName() + ": Actual Invocation duration - "
                        + mPureLocalDuration / 1000000 + "ms");

                // Collect execution statistics
                profiler.stopAndLogExecutionInfoTracking(prepareDataDuration, mPureLocalDuration);
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                Log.w(TAG, "Exception while running the method locally: " + e);
                profiler.stopAndDiscardExecutionInfoTracking();
                e.printStackTrace();
            }

            if (useAnimationServer) {
                if (task.m.getName().equals("localGpuMatrixMul")) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_LOCAL_CUDA_FINISHED);
                } else {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_LOCAL_FINISHED);
                }
            }

            return result;
        }

        /**
         * Execute the method on a phone that is close to us so that we can connect on D2D mode. If we
         * are here, it means that this phone is not connected to a clone, so we can define the clone to
         * be this D2D device.
         *
         * @param otherPhone Is the closeby phones, which is willing to help with offloading.
         * @return
         * @throws IllegalArgumentException
         * @throws SecurityException
         */
        private Object executeD2D(Task task, PhoneSpecs otherPhone) {

            // otherPhone.setIp("192.168.43.1");
            Log.i(TAG, "Trying to execute the method using D2D on device: " + otherPhone);
            Object result = null;
            try (Socket socket = new Socket(otherPhone.getIp(), config.getClonePort());
                 InputStream is = socket.getInputStream();
                 OutputStream os = socket.getOutputStream();
                 ObjectInputStream ois = new ObjectInputStream(is);
                 ObjectOutputStream oos = new ObjectOutputStream(os)) {

                sendApk(is, os, oos);
                result = executeRemotely(task, os, ois, oos);
            } catch (Exception e) {
                Log.w(TAG, "Exception while trying to connect with other phone: " + e);
            }
            return result;
        }

        /**
         * Execute method remotely
         *
         * @return result of execution, or an exception if it happened
         * @throws SecurityException
         * @throws IllegalArgumentException If the arguments passed to the method are not correct.
         */
        private Object executeRemotely(Task task, OutputStream os, ObjectInputStream ois,
                                       ObjectOutputStream oos) {

            // Maybe the developer has implemented the prepareDataOnClient() method that helps him prepare
            // the data based on where the execution will take place then call it.
            try {
                if (useAnimationServer)
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_PREPARE_DATA);
                long s = System.nanoTime();
                Method prepareDataMethod = task.o.getClass().getDeclaredMethod("prepareDataOnClient");
                prepareDataMethod.setAccessible(true);
                prepareDataMethod.invoke(task.o);
                prepareDataDuration = System.nanoTime() - s;
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "The method prepareDataOnClient() does not exist");
            } catch (InvocationTargetException | IllegalAccessException | IllegalArgumentException e) {
                Log.e(TAG, "Exception while calling method prepareDataOnClient(): " + e);
                e.printStackTrace();
            }

            if (useAnimationServer) {
                if (nrClones > 1) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_DECISION_OFFLOAD_2VMs_AS);
                } else {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_DECISION_OFFLOAD_AS);
                }
            }

            ProgramProfiler progProfiler = new ProgramProfiler(mAppName, task.m.getName());
            DeviceProfiler devProfiler = new DeviceProfiler(mContext);
            NetworkProfiler netProfiler = new NetworkProfiler(mContext, config);
            Profiler profiler = new Profiler(mRegime, progProfiler, netProfiler, devProfiler);

            // Start tracking execution statistics for the method
            profiler.startExecutionInfoTracking();

            Object result;
            try {
                Long startTime = System.nanoTime();
                os.write(RapidMessages.AC_OFFLOAD_REQ_AS);
                result = sendAndExecute(task, ois, oos);

                Long duration = System.nanoTime() - startTime;
                Log.d(TAG, "REMOTE " + task.m.getName() + ": Actual Send-Receive duration - "
                        + duration / 1000000 + "ms");

                // Collect execution statistics
                if (result instanceof InvocationTargetException || result instanceof Exception) {
                    profiler.stopAndDiscardExecutionInfoTracking();
                } else {
                    profiler.stopAndLogExecutionInfoTracking(prepareDataDuration, mPureRemoteDuration);
                }
            } catch (Exception e) {
                // No such host exists, execute locally
                fallBackToLocalExecution("REMOTE ERROR: " + task.m.getName() + ": " + e);
                e.printStackTrace();
                profiler.stopAndDiscardExecutionInfoTracking();
                closeConnection();
                return e;
//                result = executeLocally(task);
                // ConnectionRepair repair = new ConnectionRepair();
                // repair.start();
            }

            if (useAnimationServer) {
                if (task.m.getName().equals("localGpuMatrixMul")) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_LOCAL_CUDA_FINISHED);
                } else if (nrClones > 1) {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_OFFLOADING_FINISHED_PARALLEL);
                } else {
                    animationMsgSender.sendAnimationMsg(AnimationMsg.AC_OFFLOADING_FINISHED);
                }
            }

            return result;
        }

        /**
         * Send the object, the method to be executed and parameter values to the remote server for
         * execution.
         *
         * @return result of the remoted method or an exception that occurs during execution
         * @throws IOException
         * @throws ClassNotFoundException
         * @throws NoSuchMethodException
         * @throws InvocationTargetException
         * @throws IllegalAccessException
         * @throws SecurityException
         * @throws IllegalArgumentException
         */
        private Object sendAndExecute(Task task, ObjectInputStream ois, ObjectOutputStream oos)
                throws IOException, ClassNotFoundException, IllegalArgumentException, SecurityException,
                IllegalAccessException, InvocationTargetException, NoSuchMethodException {

            // Send the object itself
            sendObject(task, oos);

            // Read the results from the server
            Log.d(TAG, "Read Result");
            Object response = ois.readObject();

            ResultContainer container = (ResultContainer) response;
            Object result;

            Class<?>[] pTypes = {Remoteable.class};
            try {
                // Use the copyState method that must be defined for all Remoteable
                // classes to copy the state of relevant fields to the local object
                task.o.getClass().getMethod("copyState", pTypes).invoke(task.o, container.objState);
            } catch (NullPointerException e) {
                // Do nothing - exception happened remotely and hence there is
                // no object state returned.
                // The exception will be returned in the function result anyway.
                Log.d(TAG, "Exception received from remote server - " + container.functionResult);
            }

            result = container.functionResult;
            mPureRemoteDuration = container.pureExecutionDuration;

            Log.d(TAG, "Finished remote execution");

            return result;
        }

        /**
         * Send the object (along with method and parameters) to the remote server for execution
         *
         * @throws IOException
         */
        private void sendObject(Task task, ObjectOutputStream oos)
                throws IOException {
            oos.reset();
            Log.d(TAG, "Write Object and data");

            // Send the number of clones needed to execute the method
            oos.writeInt(nrClones);

            // Send object for execution
            oos.writeObject(task.o);

            // Send the method to be executed
            // Log.d(TAG, "Write Method - " + m.getName());
            oos.writeObject(task.m.getName());

            // Log.d(TAG, "Write method parameter types");
            oos.writeObject(task.m.getParameterTypes());

            // Log.d(TAG, "Write method parameter values");
            oos.writeObject(task.pValues);
            oos.flush();
        }
    }

    private class RapidBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "Got an intent from Rapid service: " + intent);
            switch (intent.getAction()) {
                case RapidNetworkService.RAPID_NETWORK_CHANGED:
                    NetworkProfiler.setRtt(intent.getIntExtra(
                            RapidNetworkService.RAPID_NETWORK_RTT, NetworkProfiler.rttInfinite));
                    NetworkProfiler.setUlRate(
                            intent.getIntExtra(RapidNetworkService.RAPID_NETWORK_UL_RATE, -1));
                    NetworkProfiler.setDlRate(
                            intent.getIntExtra(RapidNetworkService.RAPID_NETWORK_DL_RATE, -1));
                    break;

                case RapidNetworkService.RAPID_VM_CHANGED:
                    sClone = (Clone) intent.getSerializableExtra(RapidNetworkService.RAPID_VM_IP);
                    config.setClone(sClone);
                    for (final TaskRunner t : taskRunnersList) {
                        new Thread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        t.registerWithVm();
                                    }
                                }
                        ).start();
                    }
                    break;

                case RapidNetworkService.RAPID_D2D_SET_CHANGED:
                    d2dSetPhones = (TreeSet<PhoneSpecs>)
                            intent.getSerializableExtra(RapidNetworkService.RAPID_D2D_SET);
                    break;
            }
        }
    }

    @SuppressWarnings("unused")
    public String getConnectionType() {
        return NetworkProfiler.currentNetworkTypeName;
    }

    public void setUserChoice(ExecLocation userChoice) {
        this.userChoice = userChoice;
    }

    @SuppressWarnings("unused")
    public ExecLocation getUserChoice() {
        return userChoice;
    }

    @SuppressWarnings("unused")
    public int getRegime() {
        return mRegime;
    }

    public void setNrVMs(int nrClones) {
        Log.i(TAG, "Changing nrClones to: " + nrClones);
        this.nrClones = nrClones;
    }

    @SuppressWarnings("unused")
    public Configuration getConfig() {
        return config;
    }

    public Context getContext() {
        return mContext;
    }

    public ExecLocation getLastExecLocation(String appName, String methodName) {
        return mDSE.getLastExecLocation(appName, methodName);
    }

    public long getLastExecDuration(String appName, String methodName) {
        return mDSE.getLastExecDuration(appName, methodName);
    }

    @SuppressWarnings("unused")
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
