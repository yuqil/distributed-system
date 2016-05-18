/**
 * This is a RPC interface for file operation of server and client.
 *
 * Author: Yuqi Liu <yuqil @andrew.cmu.edu>
 * Data: 02/26
 */
import java.io.RandomAccessFile;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteFile extends Remote {

	/**
	 * Open a file, return its matadata
	 * @param path: file path
	 * @param option: 1-CREATE, 2-CREATE NEW 3-READ 4- WRITE
	 * @param version: current version in cache
	 * @return FileData class contains file metadata
	 * @throws RemoteException
     */
	 FileData open(String path, int option, long version) throws RemoteException;

	/**
	 * Close a file with writeBack data in a single RPC call
	 * @param path: file path
	 * @param writeBack: write back data
	 * @return 0 for success, -1 for failure
	 * @throws RemoteException
     */
	 long close(String path, FileData writeBack) throws RemoteException;

	/**
	 * Close a file when the file cannot be write back with single RPC call
	 * This needs chunking. So the data is written to a shallow copy first.
	 *
	 * @param path: file path
	 * @param tem_path: shallow copy path
	 * @return 0 for success, -1 for failure
	 * @throws RemoteException
	 */
	 long close(String tem_path, String path) throws RemoteException;

	/**
	 * Unlink a file
	 * @param path file path
	 * @return String of Error Message, null if no error
	 * @throws RemoteException
     */
	 String unlink(String path) throws RemoteException;

	/**
	 * Read a file from pointer offset
	 * @param path file path
	 * @param offset current file pointer
	 * @return FileReadData class containing of ReadData and next read pointer
	 * @throws RemoteException
     */
	 FileReadData read(String path, long offset) throws RemoteException;

	/**
	 * Write bytes of size to a file from buf. The write begins from offset
	 * @param path file path
	 * @param offset current file pointer
	 * @param buf write data
	 * @param size write size
	 * @return next write pointer after write, -1 when error occurs
	 * @throws RemoteException
     */
	 long write(String path, long offset, byte[] buf, int size) throws RemoteException;
}
