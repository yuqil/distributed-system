/*
 * This is a program for Database cache.
 * Author: Yuqi Liu
 */

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DB extends UnicastRemoteObject implements Cloud.DatabaseOps  {
	private static final long serialVersionUID = 1L;
	// cache in memory
	private Map<String, String> map = new ConcurrentHashMap<String, String>();

	// SL object
	private static ServerLib SL;

	// db object
	private static Cloud.DatabaseOps database;

	/**
	 * Cache constructor
	 * @param host  host of RMI
	 * @param str_port  port of RMI
	 * @throws RemoteException
	 */
	protected DB(String host, String str_port) throws RemoteException {
		super();
		int port =  Integer.parseInt(str_port);
		SL = new ServerLib(host, port);
		database = SL.getDB();
	}

	/**
	 * Get a reply
	 * @param key request key
	 * @return reply value
	 * @throws RemoteException
	 */
	@Override
	public String get(String key) throws RemoteException {
		if (map.containsKey(key)) return map.get(key);
		String value = database.get(key);
		map.put(key, value);
		return value;
	}

	/**
	 * Set a given request, pass to DB
	 * @param arg0
	 * @param arg1
	 * @param arg2
	 * @return
	 * @throws RemoteException
	 */
	@Override
	public boolean set(String arg0, String arg1, String arg2) throws RemoteException {
		return database.set(arg0, arg1, arg2);
	}

	/**
	 * Real transaction, pass to DB
	 * @param arg0
	 * @param arg1
	 * @param arg2
	 * @return
	 * @throws RemoteException
	 */
	@Override
	public boolean transaction(String arg0, float arg1, int arg2) throws RemoteException {
		return database.transaction(arg0, arg1, arg2);
	}

}
