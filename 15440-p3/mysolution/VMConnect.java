/*
 * This is a remote interface for autoscaling.
 * Author: Yuqi Liu
 */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;


public interface VMConnect extends Remote {
	/**
	 * Scale in middle tier, shut down certain VM
	 * @param id VM id
	 * @return True for success.
	 * @throws RemoteException
	 */
	public boolean scaleIn_middle(int id) throws RemoteException;

	/**
	 * Scale in frontend, shut down certain VM.
	 * @param id VM id
	 * @return True for success.
	 * @throws RemoteException
	 */
	public boolean scaleIn_frontend (int id) throws RemoteException;


	/**
	 * Get the role of the VM from master
	 * @param id VM id
	 * @return Role Number (2 for Cache, 3 for middle layer, 0 for frontend)
	 * @throws RemoteException
	 */
	public int getRole(int id) throws RemoteException;


	/**
	 * Push a request to the request queue
	 * @param r Request r
	 * @throws RemoteException
	 */
	public void addRequest(Cloud.FrontEndOps.Request r) throws RemoteException;


	/**
	 * Pull a request from request queue
	 * @return Request from queue head
	 * @throws RemoteException
	 */
	public Cloud.FrontEndOps.Request getRequest() throws RemoteException;


	/**
	 * Register a new VM on the master server.
	 * @param id VM_id
	 * @param type 0 for frontend, 2 for middle layer
	 * @throws RemoteException
	 */
	public void addVM(int id, int type) throws RemoteException;

	/**
	 * Terminate the machine itself.
	 * Shut down RMI and any other records.
	 * @throws RemoteException
	 */
	public void terminate() throws RemoteException;


	/**
	 * If the request should be drop.
	 * Based on current queue length
	 * @return True for drop, False for not drop
	 * @throws RemoteException
	 */
	public boolean drop() throws RemoteException;


	/**
	 * Get the cache VM id.
	 * @return Cache VM id
	 * @throws RemoteException
	 */
	public int getCacheId() throws RemoteException;


	/**
	 * Get cache object.
	 * @return A DatabaseOps interface object
	 * @throws RemoteException
	 */
	public Cloud.DatabaseOps getCache() throws RemoteException;

	/**
	 * Scale out for type
	 * @param type: 0 for frontend, 1 for middle
	 * @throws RemoteException
     */
	public void scaleOut(int type) throws RemoteException;
}

