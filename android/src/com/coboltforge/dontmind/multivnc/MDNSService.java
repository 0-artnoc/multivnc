package com.coboltforge.dontmind.multivnc;

/**
 * @author Christian Beier
 */

import java.io.IOException;
import java.util.Hashtable;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceListener;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class MDNSService extends Service {

	private final String TAG = "MDNSService";
	private final IBinder mBinder = new LocalBinder();
	
	private BroadcastReceiver netStateChangedReceiver;
	
	private MDNSWorkerThread workerThread = new MDNSWorkerThread();
	
	private ImDNSNotify callback;


	
	/**
	 * Class for clients to access. Because we know this service always runs in
	 * the same process as its clients, we don't need to deal with IPC.
	 */
	public class LocalBinder extends Binder {
		MDNSService getService() {
			return MDNSService.this;
		}
	}




	@Override
	public IBinder onBind(Intent intent) {
		// handleIntent(intent);
		return mBinder;
	}



	@Override
	public void onCreate() {
		//code to execute when the service is first created
		Log.d(TAG, "mDNS service onCreate()!");
		
		workerThread.start();

		// listen for scan results 
		netStateChangedReceiver = new BroadcastReceiver() {
			//@Override
			public void onReceive(Context context, Intent intent)
			{
				boolean no_net = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
				Log.d(TAG, "Connectivity changed, still sth. available: " +  !no_net + " " + intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO).toString());

				// we get this as soon as we're registering, see http://stackoverflow.com/questions/6670514/connectivitymanager-connectivity-action-always-broadcast-when-registering-a-rec
				// thus it's okay to have the (re-)startup here!
				Message.obtain(workerThread.handler, MDNSWorkerThread.MESSAGE_STOP).sendToTarget();

				if(!no_net) // only (re)start when we actually have connection
					Message.obtain(workerThread.handler, MDNSWorkerThread.MESSAGE_START).sendToTarget();
				
			}
		};
		registerReceiver(netStateChangedReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

	}


	@Override
	public void onDestroy() {
		//code to execute when the service is shutting down
		Log.d(TAG, "mDNS service onDestroy()!");
		
		unregisterReceiver(netStateChangedReceiver);
		
		// this will end the worker thread
		workerThread.interrupt();
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startid) {
		//code to execute when the service is starting up
		Log.d(TAG, "mDNS service onStartCommand()!");

		if(intent == null)
			Log.d(TAG, "Restart!");

		return START_STICKY;
	} 
	
	// NB: this is called from the worker thread!!
	public void registerCallback(ImDNSNotify c) {
		callback = c;
	}

	// force a callback call for every conn in connections_discovered
	public void dump() {
		Message.obtain(workerThread.handler, MDNSWorkerThread.MESSAGE_DUMP).sendToTarget();
	}

	
	private class MDNSWorkerThread extends Thread
	{
		private android.net.wifi.WifiManager.MulticastLock multicastLock;
		private String mdnstype = "_rfb._tcp.local.";
		private JmDNS jmdns = null;
		private ServiceListener listener = null;
		private Hashtable<String,ConnectionBean> connections_discovered = new Hashtable<String,ConnectionBean> (); 
		private Handler handler;
		
		public final static int MESSAGE_START = 0;
		public final static int MESSAGE_STOP = 1;
		public final static int MESSAGE_DUMP = 2;


		// this just runs a message loop and acts according to messages
		public void run() {
			Looper.prepare();
			
			handler = new Handler() {
	            
				public void handleMessage(Message msg) {
					// if interrupted, bail out at once
					if(isInterrupted())
					{
						Log.d(TAG, "INTERRUPTED, bailing out!");
						mDNSstop();
						getLooper().quit();
						return;
					}
					// otherwise, process our message queue

					switch (msg.what) {
					case MDNSWorkerThread.MESSAGE_START:
						mDNSstart();
						break;
					case MDNSWorkerThread.MESSAGE_STOP:
						mDNSstop();
						break;
					case MDNSWorkerThread.MESSAGE_DUMP:
						for(ConnectionBean c : connections_discovered.values()) {
							mDNSnotify(c.getNickname(), c);
						}
						break;
					}
				}
				
	          };
			
			Looper.loop();
		}
		
		
		private void mDNSstart()
		{
			Log.d(TAG, "starting MDNS " + JmDNS.VERSION);
			
			if(jmdns != null) {
				Log.d(TAG, "MDNS already running, bailing out");
				return;
			}
			
			android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager) getSystemService(android.content.Context.WIFI_SERVICE);
			multicastLock = wifi.createMulticastLock("mylockthereturn");
			multicastLock.setReferenceCounted(true);
			multicastLock.acquire();
			try {
				jmdns = JmDNS.create();
				jmdns.addServiceListener(mdnstype, listener = new ServiceListener() {

					@Override
					public void serviceResolved(ServiceEvent ev) {
						ConnectionBean c = new ConnectionBean();
						c.set_Id(0); // new!
						c.setNickname(ev.getName());
						c.setAddress(ev.getInfo().getInetAddresses()[0].toString().replace('/', ' ').trim());
						c.setPort(ev.getInfo().getPort());
						
						connections_discovered.put(ev.getInfo().getQualifiedName(), c);
					
						Log.d(TAG, "discovered server :" + ev.getName() 
									+ ", now " + connections_discovered.size());
						
						mDNSnotify(ev.getName(), c);
					}

					@Override
					public void serviceRemoved(ServiceEvent ev) {
						connections_discovered.remove(ev.getInfo().getQualifiedName());
						
						Log.d(TAG, "server gone:" + ev.getName() 
								+ ", now " + connections_discovered.size());
						
						mDNSnotify(ev.getName(), null);
					}

					@Override
					public void serviceAdded(ServiceEvent event) {
						// Required to force serviceResolved to be called again (after the first search)
						jmdns.requestServiceInfo(event.getType(), event.getName(), 1);
					}
				});

			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}

		private void mDNSstop()
		{
			Log.d(TAG, "stopping MDNS");
			if (jmdns != null) {
				if (listener != null) {
					jmdns.removeServiceListener(mdnstype, listener);
					listener = null;
				}
				try {
					jmdns.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				jmdns = null;
			}

			if(multicastLock != null) {
				multicastLock.release();
				multicastLock = null;
			}
			
			// notify our callback about our internal state, i.e. the removals
			for(ConnectionBean c: connections_discovered.values()) 
				mDNSnotify(c.getNickname(), null);
			// and clear internal state
			connections_discovered.clear();
			
			Log.d(TAG, "stopping MDNS done");
		}

		// do the GUI stuff in Runnable posted to main thread handler
		private void mDNSnotify(final String conn_name, final ConnectionBean conn) {
			if(callback!=null)
				callback.mDNSnotify(conn_name, conn, connections_discovered);
			else
				Log.d(TAG, "callback is NULL, not notifying");

		}
		
		
	}
	
	
}
