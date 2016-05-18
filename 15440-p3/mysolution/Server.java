/*
 * This is a program for AutoScaling.
 * Author: Yuqi Liu
 */

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


public class Server extends UnicastRemoteObject implements VMConnect{
	/* Attributes of the server */
	public static float time = 0;       // current time
	public static int role = -1;        // server role
	public static int id = -1;          // vm id
	public static int port = 0;         // RMI port
	public static ServerLib SL;
	public static DB cache = null;
	public static int cache_id = 1;

	/* Scale Policy Parameters */
	public static final double frontend_weight = 1.5;
	public static final double mid_weight = 1.5;      // middle scale out weight
	public static final double drop_weight = 1.15;    // drop rate for queue length
	public static final double initial_weight = 0.7;  // initial number of VMs
	public static final int timeout_middle = 600;     // timeout value
	public static final int timeout_num = 3;          // timeout number for frontend
	public static final int timeout_num_middle = 2;   // timeout number for middle

	/* Current running VM lists */
	public static List<Integer> VMs_middle = new ArrayList<Integer>();
	public static List<Integer> VMs_frontend = new ArrayList<Integer>();
	public static ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<Integer, Integer>();
	// master request queues for all middle server to pull
	public static BlockingQueue<Cloud.FrontEndOps.Request> queue = new LinkedBlockingQueue<Cloud.FrontEndOps.Request>();
	public static double frontLen = 0;
	public static double middleLen = 0;
	public static boolean terminate = false;

	/* GLOBAL MACRO */
	public static final int FRONTEND = 0;
	public static final int MIDDLE_TIER = 1;
	public static final int CACHE = 2;
	public static final int MASTER = 3;
	public static final int FRONTEND_PROCESS_TIME = 260;
	public static final int COOL_DOWN_TIME = 7000;
	public static final int CACHE_BOOT = 500;

	protected Server() throws RemoteException {
		super();
	}

	public static void main ( String args[] ) throws Exception {
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <vm_number>");
		port =  Integer.parseInt(args[1]);
		SL = new ServerLib(args[0], port);

		/* Register for RMI service */
		id = Integer.parseInt(args[2]);
		Server server = null;
		VMConnect master = null;       // Master server RMI
		Server slave = null;           // Slave server RMI
		Registry registry = LocateRegistry.getRegistry(port);
		try {
			server = new Server();
			registry.bind("VM_Connect", server);
		} catch (Exception e) {
			server = null;
			master = (VMConnect) Naming.lookup("//localHost:"+ port + "/VM_Connect");
			slave = new Server();
			try { registry.bind("VM_Connect_" + id, slave);
			} catch (Exception e2) { System.out.println(e2.getMessage());}
		}

		/* Initial setting for VMs given request intervals */
		int middle_num = 0;     // initial middle number
		int front_num = 0;      // initial frontend number
		if (server != null) {
			role = MASTER;

			// setting cache on the master
			cache = new DB(args[0], args[1]);
			map.put(id, CACHE);
			SL.register_frontend();
			map.put(id, FRONTEND);
			VMs_frontend.add(id);
			map.put(SL.startVM(), MIDDLE_TIER);

			// measuring request interval while booting
			Cloud.FrontEndOps.Request tmp = SL.getNextRequest();
			long first = System.currentTimeMillis();
			SL.drop(tmp);
			tmp = SL.getNextRequest();
			long second = System.currentTimeMillis();
			SL.drop(tmp);
			int interval = (int) (second - first) + CACHE_BOOT;

			// if interval is faster than frontend process time, open one more frontend
			if (interval < FRONTEND_PROCESS_TIME) {
				front_num = 1;
			}

			// open middle layer according to the interval at first
			middle_num = (int) ((Cloud.CLIENT_BROWSE_TIMEOUT / interval) * initial_weight) + 1;

			// Start VMs according to initial setting
			for (int i = 0; i < middle_num; i ++)
				map.put(SL.startVM(), MIDDLE_TIER);
			for (int i = 0; i < front_num; i ++)
				map.put(SL.startVM(), FRONTEND);
		}

		/* Get the role of the VM from master */
		while (role == -1) { role = master.getRole(id); }
		System.out.println("Role: " + role);

		/* Enter Service Routine for Different Role */
		if (role == FRONTEND) {
			frontendRoutine(master);
		} else if (role == MIDDLE_TIER) {
			middleRoutine(master);
		} else if (role == MASTER) {
			masterRoutine(master, middle_num + 1);
		}
	}

	/**
	 * Master service routine function.
	 * Act as a front end and cache, checking for scale out.
	 * @param master master RMI object
	 * @param middle_num initial middle tier number
	 * @throws RemoteException
	 * @throws InterruptedException
	 */
	private static void masterRoutine(VMConnect master, int middle_num) throws RemoteException, InterruptedException {
		// Drop requests when booting
		while (VMs_middle.size() == 0) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			queue.add(r);
			if (queue.size() > middle_num) {
				SL.drop(queue.poll());
			}
		}
		frontLen = VMs_frontend.size();
		middleLen = VMs_middle.size();
		
		long time = 0;
		while (true) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			queue.put(r);

			// get middle and front number
			int front = 0;
			int middle = 0;
			for (int val : map.values()) {
				if (val == FRONTEND) front ++;
				if (val == MIDDLE_TIER) middle ++;
			}

			// scale up when frontend queue is too long
			frontLen = SL.getQueueLength();
			if (frontLen >= 3 * front) {
				long now = System.currentTimeMillis();
				if (now - time > COOL_DOWN_TIME) {
					scaleOut(SL, FRONTEND);
					time = now;
				}
			}
		}
	}

	/**
	 * Front end routine. Get the request and push to request queue.
	 * @param master Master server RMI
	 * @throws RemoteException
	 */
	private static void frontendRoutine(VMConnect master) throws RemoteException {
		SL.register_frontend();
		master.addVM(id, FRONTEND);
		int timeouts = 0;
		long start = System.currentTimeMillis();
		while (true) {
			Cloud.FrontEndOps.Request r = SL.getNextRequest();
			master.addRequest(r);
			long end = System.currentTimeMillis();

			// if timeout for two times, scale out
			if (end - start > 2 * FRONTEND_PROCESS_TIME) {
				timeouts ++;
				// if timeout, scale in
				if (timeouts == 2) {
					System.out.println("ScaleIn frontend!");
					master.scaleIn_frontend(id);
				} 
			} else {
				timeouts = 0;
			}
			start = end;
		}
	}


	/**
	 * Middle layer machine service routine.
	 * @param master
	 * @throws RemoteException
	 * @throws MalformedURLException
	 * @throws NotBoundException
	 */
	private static void middleRoutine (VMConnect master) throws RemoteException, MalformedURLException, NotBoundException {
		master.addVM(id, MIDDLE_TIER);

		// get cache
		cache_id = master.getCacheId();
		while (cache_id == -1) { cache_id = master.getCacheId(); }
		int timeouts = 0;
		int drops = 0;
		Cloud.DatabaseOps db = master.getCache();

		// check for scale policy
		while (true) {
			Cloud.FrontEndOps.Request r = master.getRequest();

			// if get a requset
			if (r != null) {
				timeouts = 0;

				// if drop for two times, scale out
				if (master.drop()) {
					SL.drop(r);
					drops ++;
					if (drops == 2) {
						master.scaleOut(MIDDLE_TIER);
						drops = 0;
					}
					continue;
				}
				drops = 0;
				SL.processRequest(r, db);
			}

			// if timeout for three times, scale in
			else if (r == null) {
				timeouts ++;
				if (timeouts == timeout_num) {
					System.out.println("ScaleIn middle!");
					master.scaleIn_middle(id);
				}
			}
		}
	}


	/**
	 * Scale in a frontend.
	 * @param id VM id
	 * @return
	 * @throws RemoteException
	 */
	public boolean scaleIn_frontend (int id) throws RemoteException {
		int remove_id = -1;
		if (VMs_frontend.size() > 1) {
			remove_id = id;
			VMs_frontend.remove(new Integer(id));
		}
		if (remove_id != -1) {
			VMConnect removed;
			try {
				removed = (VMConnect) Naming.lookup("//localHost:"+ port + "/VM_Connect_" + remove_id);
				removed.terminate();
			} catch (MalformedURLException | RemoteException | NotBoundException e) {
				e.printStackTrace();
			}
			map.remove(remove_id);
			SL.endVM(remove_id);
			return true;
		}
		return false;
	}

	/**
	 * Scale in a middle.
	 * @param id VM id
	 * @return
	 * @throws RemoteException
	 */
	public boolean scaleIn_middle (int id) throws RemoteException {
		int remove_id = -1;
		if (VMs_middle.size() > 1) {
			remove_id = id;
			VMs_middle.remove(new Integer(id));
		} 
		
		if (remove_id != -1) {
			VMConnect removed;
			try {
				removed = (VMConnect) Naming.lookup("//localHost:"+ port + "/VM_Connect_" + remove_id);
				removed.terminate();
			} catch (MalformedURLException | RemoteException | NotBoundException e) {
				e.printStackTrace();
			}
			map.remove(remove_id);
			SL.endVM(remove_id);
			return true;
		}
		return false;
	}


	/**
	 * Scale out
	 * @param SL
	 * @param type: 0 for front end, 1 for middle layer
     */
	public static void scaleOut(ServerLib SL, int type) {
		System.out.println("Scale Out!");
		map.put(SL.startVM(), type);
	}

	/**
	 * Scale out
	 * @param type: 0 for front end, 1 for middle layer
     */
	public void scaleOut(int type) throws RemoteException {
		System.out.println("Scale Out!");
		map.put(SL.startVM(), type);
	}

	/**
	 * Terminate the machine itself.
	 * Shut down RMI and any other records.
	 * @throws RemoteException
	 */
	public void terminate() throws RemoteException {
		terminate = true;
		SL.unregister_frontend();
		UnicastRemoteObject.unexportObject(this, true);
	}


	/**
	 * Get the role of the VM from master
	 * @param id VM id
	 * @return Role Number (2 for Cache, 3 for middle layer, 0 for frontend)
	 * @throws RemoteException
	 */
	public int getRole(int id) throws RemoteException {
		if(map.containsKey(id)) return map.get(id);
		else return -1;
	}


	/**
	 * Push a request to the request queue
	 * @param r Request r
	 * @throws RemoteException
	 */
	public void addRequest(Cloud.FrontEndOps.Request r) throws RemoteException {
		queue.add(r);
	}


	/**
	 * Pull a request from request queue
	 * @return Request from queue head
	 * @throws RemoteException
	 */
	public Cloud.FrontEndOps.Request getRequest() throws RemoteException {
		try {
			return queue.poll(timeout_middle, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return null;
	}


	/**
	 * Register a new VM on the master server.
	 * @param id VM_id
	 * @param type 0 for frontend, 2 for middle layer
	 * @throws RemoteException
	 */
	public void addVM(int id, int type) throws RemoteException {
		if (type == FRONTEND) {
			if (!VMs_frontend.contains(id)) VMs_frontend.add(id);
		} else if (type == MIDDLE_TIER) {
			if (!VMs_middle.contains(id)) VMs_middle.add(id);
		} else if (type == CACHE) {
			cache_id = id;
		}
	}


	/**
	 * If the request should be drop.
	 * Based on current queue length
	 * @return True for drop, False for not drop
	 * @throws RemoteException
	 */
	public boolean drop() throws RemoteException {
		int middle = 0;
		for (int val : map.values()) {
			if (val == MIDDLE_TIER) {
				middle ++;
			}
		}
		return queue.size() > (drop_weight * middle);
	}


	/**
	 * Get the cache VM id.
	 * @return Cache VM id
	 * @throws RemoteException
	 */
	public int getCacheId() throws RemoteException {
		int tmp = cache_id;
		return tmp;
	}


	/**
	 * Get cache object.
	 * @return A DatabaseOps interface object
	 * @throws RemoteException
	 */
	public Cloud.DatabaseOps getCache() throws RemoteException {
		Cloud.DatabaseOps tmp = cache;
		return tmp;
	}
}

