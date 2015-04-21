/*
 * Copyright (C) 2014 Disrupted Systems
 *
 * This file is part of Rumble.
 *
 * Rumble is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Rumble is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rumble.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.disrupted.rumble.network;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.disrupted.rumble.R;
import org.disrupted.rumble.network.protocols.firechat.FirechatProtocol;
import org.disrupted.rumble.network.services.ServiceLayer;
import org.disrupted.rumble.network.services.chat.ChatService;
import org.disrupted.rumble.network.services.push.PushService;
import org.disrupted.rumble.userinterface.activity.RoutingActivity;
import org.disrupted.rumble.network.linklayer.events.LinkLayerStopped;
import org.disrupted.rumble.network.linklayer.events.NeighborhoodChanged;
import org.disrupted.rumble.network.protocols.events.NeighbourConnected;
import org.disrupted.rumble.network.protocols.events.NeighbourDisconnected;
import org.disrupted.rumble.network.linklayer.events.NeighbourReachable;
import org.disrupted.rumble.network.linklayer.events.NeighbourUnreachable;
import org.disrupted.rumble.network.linklayer.LinkLayerNeighbour;
import org.disrupted.rumble.network.linklayer.Scanner;
import org.disrupted.rumble.network.linklayer.bluetooth.BluetoothLinkLayerAdapter;
import org.disrupted.rumble.network.linklayer.LinkLayerAdapter;
import org.disrupted.rumble.network.linklayer.wifi.WifiManagedLinkLayerAdapter;
import org.disrupted.rumble.network.protocols.Protocol;
import org.disrupted.rumble.network.protocols.rumble.RumbleProtocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.greenrobot.event.EventBus;
import de.greenrobot.event.NoSubscriberEvent;


/**
 * NetworkCoordinator coordinate every network related events. It maintain an up-to-date
 * view of the neighbourhood history (past and present). It works closely with the NeighbourManager
 * which is responsible of a specific neighbour.
 *
 * This class is running in its own thread from boot-time of the application.
 *
 * @author Marlinski
 */
public class NetworkCoordinator extends Service {

    public static final String ACTION_START_FOREGROUND = "org.disruptedsystems.rumble.action.startforeground";
    public static final String ACTION_STOP_NETWORKING  = "org.disruptedsystems.rumble.action.stopnetworking";
    public static final String ACTION_MAIN_ACTION      = "org.disruptedsystems.rumble.action.mainaction";
    public static final int    FOREGROUND_SERVICE_ID   = 4242;

    private static final String TAG = "NetworkCoordinator";

    private static final Object lock = new Object();

    private List<LinkLayerAdapter>  adapters;
    private Map<String, WorkerPool> workerPools;
    private List<Protocol>          protocols;
    private List<ServiceLayer>      services;

    private List<Scanner> scannerList;
    public NeighbourManager neighbourManager;

    public boolean networkingStarted;

    private final IBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        public NetworkCoordinator getService() {
            return NetworkCoordinator.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (lock) {
            Log.d(TAG, "[+] Starting NetworkCoordinator");

            neighbourManager = new NeighbourManager();
            neighbourManager.startMonitoring();
            scannerList = new LinkedList<Scanner>();

            // register link layers
            adapters = new LinkedList<LinkLayerAdapter>();
            LinkLayerAdapter bluetoothAdapter = new BluetoothLinkLayerAdapter(this);
            adapters.add(bluetoothAdapter);
            LinkLayerAdapter wifiAdapter = new WifiManagedLinkLayerAdapter(this);
            adapters.add(wifiAdapter);

            // create worker pools
            workerPools = new HashMap<String, WorkerPool>();
            WorkerPool bluetoothWorkers = new WorkerPool(5);
            workerPools.put(bluetoothAdapter.getLinkLayerIdentifier(), bluetoothWorkers);
            WorkerPool wifiManagedWorkers = new WorkerPool(5);
            workerPools.put(wifiAdapter.getLinkLayerIdentifier(), wifiManagedWorkers);

            // register protocols
            protocols = new LinkedList<Protocol>();
            protocols.add(new RumbleProtocol(this));
            protocols.add(new FirechatProtocol(this));

            // register services
            services = new LinkedList<ServiceLayer>();
            services.add(PushService.getInstance());
            services.add(ChatService.getInstance());

            networkingStarted = false;
            EventBus.getDefault().register(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (lock) {
            Log.d(TAG, "[-] Destroy NetworkCoordinator");

            services.clear();
            services = null;
            protocols.clear();
            protocols = null;
            workerPools.clear();
            workerPools = null;
            adapters.clear();
            adapters = null;

            neighbourManager.stopMonitoring();

            if(EventBus.getDefault().isRegistered(this))
                EventBus.getDefault().unregister(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (lock) {
            if (intent == null)
                return START_STICKY;

            if (intent.getAction().equals(ACTION_START_FOREGROUND)) {
                Log.d(TAG, "Received Start Foreground Intent ");

                startNetworking();

                Intent notificationIntent = new Intent(this, RoutingActivity.class);
                notificationIntent.setAction(ACTION_MAIN_ACTION);
                notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                        notificationIntent, 0);

                Notification notification = new NotificationCompat.Builder(this)
                        .setContentTitle("Rumble")
                        .setTicker("Rumble started")
                        .setContentText("Rumble started")
                        .setSmallIcon(R.drawable.small_icon)
                        .setContentIntent(pendingIntent)
                        .setOngoing(true).build();

                startForeground(FOREGROUND_SERVICE_ID, notification);
            }

            if(intent.getAction().equals(ACTION_STOP_NETWORKING)) {
                Log.d(TAG, "STOP ?");
                stopNetworking();
            }
        }

        return START_STICKY;
    }

    public void startNetworking() {
        if(networkingStarted)
            return;
        networkingStarted = true;

        // start the services
        for (ServiceLayer service : services) {
            service.startService();
        }

        // start the protocol
        for (Protocol protocol : protocols) {
            protocol.protocolStart();
        }

        // start the link layers and worker pools
        for (LinkLayerAdapter adapter : adapters) {
            WorkerPool pool = workerPools.get(adapter.getLinkLayerIdentifier());
            pool.startPool();
            adapter.linkStart();
        }

    }

    public void stopNetworking() {
        if(!networkingStarted)
            return;
        networkingStarted = false;
        // stop services
        for(ServiceLayer service : services) {
            service.stopService();
        }
        // destroy protocols
        for (Protocol protocol : protocols) {
            protocol.protocolStop();
        }
        // destroy worker pools
        for (Map.Entry<String, WorkerPool> entry : workerPools.entrySet()) {
            entry.getValue().stopPool();
            entry.setValue(null);
        }
        // stop link layers
        for (LinkLayerAdapter adapter : adapters) {
            adapter.linkStop();
        }
    }


    public boolean isLinkLayerEnabled(String linkLayerIdentifier) {
        synchronized (lock) {
            if (adapters == null)
                return false;
            for (LinkLayerAdapter adapter : adapters) {
                if(adapter.getLinkLayerIdentifier().equals(linkLayerIdentifier))
                    return ((adapter != null) && adapter.isActivated());
            }
            return false;
        }
    }
    public boolean isScanning() {
        synchronized (lock) {
            for (Iterator<Scanner> it = scannerList.iterator(); it.hasNext(); ) {
                Scanner scanner = it.next();
                if(scanner.isScanning())
                    return true;
            }
            return false;
        }
    }
    public void    forceScan() {
        synchronized (lock) {
            for (Iterator<Scanner> it = scannerList.iterator(); it.hasNext(); ) {
                Scanner scanner = it.next();
                scanner.forceDiscovery();
            }
        }
    }

    public void addScanner(Scanner scanner) {
        for (Iterator<Scanner> it = scannerList.iterator(); it.hasNext(); ) {
            Scanner element = it.next();
            if(element == scanner)
                return;
        }
        scannerList.add(scanner);
    }

    public void delScanner(Scanner scanner) {
        for (Iterator<Scanner> it = scannerList.iterator(); it.hasNext(); ) {
            Scanner element = it.next();
            if(element == scanner) {
                it.remove();
                return;
            }
        }
    }

    public boolean addWorker(Worker worker) {
        synchronized (lock) {
            if(workerPools == null)
                return false;
            String linkLayerIdentifier = worker.getLinkLayerIdentifier();
            if(workerPools.get(linkLayerIdentifier) == null)
                return false;
            workerPools.get(linkLayerIdentifier).addWorker(worker);
                return true;
        }
    }

    public final List<Worker> getWorkers(String linkLayerIdentifier, String protocolIdentifier, boolean active) {
        synchronized (lock) {
            if(workerPools == null)
                return null;

            WorkerPool pool = workerPools.get(linkLayerIdentifier);
            return pool.getWorkers(protocolIdentifier, active);
        }
    }

    public void stopWorkers(String linkLayerIdentifier, String protocolIdentifier) {
        synchronized (lock) {
            if(workerPools == null)
                return;

            WorkerPool pool = workerPools.get(linkLayerIdentifier);
            if(pool != null)
                pool.stopWorkers(protocolIdentifier);
        }
    }

    public void stopWorker(String linkLayerIdentifier, String workerID) {
        synchronized (lock) {
            if(workerPools == null)
                return;

            WorkerPool pool = workerPools.get(linkLayerIdentifier);
            if(pool != null)
                pool.stopWorker(workerID);
        }
    }

    /*
     * Just to avoid warning in logcat
     */
    public void onEvent(NoSubscriberEvent event) {
    }

    public class NeighbourManager {

        private final Object managerLock = new Object();
        private HashMap<String, LinkLayerNeighbour> physicalNeighbours; // linkLayerAddress to LinkLayerNeighbour Object
        private HashMap<String, Set<String>> connectedProtocols; // linkLayerAddress to connected protocols

        public NeighbourManager() {
            physicalNeighbours = new HashMap<String, LinkLayerNeighbour>();
            connectedProtocols = new HashMap<String, Set<String>>();
        }

        public void startMonitoring() {
            EventBus.getDefault().register(this);
        }
        public void stopMonitoring() {
            synchronized (managerLock) {
                if (EventBus.getDefault().isRegistered(this))
                    EventBus.getDefault().unregister(this);

                physicalNeighbours.clear();
                for (Map.Entry<String, Set<String>> entry : connectedProtocols.entrySet()) {
                    if (entry.getValue() != null)
                        entry.getValue().clear();
                }
                connectedProtocols.clear();
            }
        }

        public void onEvent(LinkLayerStopped event) {
            synchronized (managerLock) {
                Iterator<Map.Entry<String, LinkLayerNeighbour>> it = physicalNeighbours.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String,LinkLayerNeighbour> entry = it.next();
                    LinkLayerNeighbour neighbour = entry.getValue();
                    if (neighbour != null) {
                        if (neighbour.getLinkLayerIdentifier().equals(event.linkLayerIdentifier)) {
                            connectedProtocols.remove(entry.getKey());
                            it.remove();
                        }
                    }
                }
            }
            EventBus.getDefault().post(new NeighborhoodChanged());
        }
        public void onEvent(NeighbourReachable event) {
            synchronized (managerLock) {
                if (physicalNeighbours.get(event.neighbour.getLinkLayerAddress()) != null)
                    return;

                physicalNeighbours.put(event.neighbour.getLinkLayerAddress(), event.neighbour);
            }
            EventBus.getDefault().post(new NeighborhoodChanged());
        }
        public void onEvent(NeighbourUnreachable event) {
            synchronized (managerLock) {
                if (connectedProtocols.get(event.neighbour.getLinkLayerAddress()) != null)
                    return;

                physicalNeighbours.remove(event.neighbour.getLinkLayerAddress());
            }
            EventBus.getDefault().post(new NeighborhoodChanged());
        }
        public void onEvent(NeighbourConnected event) {
            synchronized (managerLock) {
                Set<String> protocolSet = connectedProtocols.get(event.neighbour.getLinkLayerAddress());
                if (protocolSet == null)
                    protocolSet = new HashSet<String>();
                protocolSet.add(event.worker.getProtocolIdentifier());
                connectedProtocols.put(event.neighbour.getLinkLayerAddress(), protocolSet);
            }
            EventBus.getDefault().post(new NeighborhoodChanged());
        }
        public void onEvent(NeighbourDisconnected event) {
            synchronized (managerLock) {
                Set<String> protocolSet = connectedProtocols.get(event.neighbour.getLinkLayerAddress());
                if (protocolSet != null) {
                    protocolSet.remove(event.worker.getProtocolIdentifier());
                    connectedProtocols.put(event.neighbour.getLinkLayerAddress(), protocolSet);
                }
                physicalNeighbours.remove(event.neighbour.getLinkLayerAddress());
            }
            EventBus.getDefault().post(new NeighborhoodChanged());
        }

        public List<NeighbourInfo> getNeighbourList() {
            synchronized (managerLock) {
                List<NeighbourInfo> ret = new LinkedList<NeighbourInfo>();
                for (Map.Entry<String, LinkLayerNeighbour> entry : physicalNeighbours.entrySet()) {
                    NeighbourInfo info = new NeighbourInfo(entry.getValue());
                    if (connectedProtocols.get(entry.getKey()) != null) {
                        for (String protocol : connectedProtocols.get(entry.getKey())) {
                            info.addProtocol(protocol);
                        }
                    }

                    ret.add(info);
                }
                return ret;
            }
        }
    }

}
