/**
 * This is a server class for concurrent remote file operation.
 * It is based on "Check-on-Use" cache protocal supporting open-close session semantics.
 * It extends RPC interface RemoteFile.
 *
 * Supports basic operaions such as open, close, write, read, unlink.
 * Every file has a ReentrantReadWriteLock to ensure atomic operation.
 *
 * When chunking data happens, make a shallow copy first and write back after all data received.
 *
 * Author: Yuqi Liu
 */

import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.registry.*;
import java.rmi.RemoteException;
import java.rmi.Naming;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class Server extends UnicastRemoteObject implements RemoteFile {
	public String rootdir = "";    // root directory
	public static File root;       // root directory file object

	// Each file has a ReentrantReadWriteLock which allows multiple readers or one wirter
	private Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
	private static final Object map_lock = new Object();  // use to lock hashmap locks when inserting
	private static final int MaxLen = 409600;             // maximum chunking size
	
	protected Server() throws RemoteException {
		super();
		// TODO Auto-generated constructor stub
	}

	/**
	 * Close a file and write back data updated. (No chunking)
	 * @param path
	 * @param writeBack: write back data
	 * @return latest version number, -1 if error
	 * @throws RemoteException
     */
	public long close(String path, FileData writeBack) throws RemoteException {
		path = rootdir + getOrigPath(path);
		try {
			// get write lock
			if (locks.get(path) != null) {
				locks.get(path).writeLock().lock();
			} else {
				synchronized(map_lock) {
					if (locks.get(path) == null) {
						locks.put(path, new ReentrantReadWriteLock());
					}
					locks.get(path).writeLock().lock();
				}
			}
			// write back
			FileOutputStream output = new FileOutputStream(path, false);
			output.write(writeBack.data);
			output.close();
			return new File(path).lastModified();
		} catch (IOException e) {
			return -1;
		} finally {
			locks.get(path).writeLock().unlock();
		}
	}

	/**
	 * Close a file when receiving in chunking.
	 * Copy from the shallow copy to the master copy after all chunk received.
	 * @param tem_path: shallow copy path
	 * @param path: master copy path
	 * @return latest version number, -1 when error
	 * @throws RemoteException
     */
	public long close(String tem_path, String path) throws RemoteException {
		path = rootdir + getOrigPath(path);
		tem_path = rootdir + getOrigPath(tem_path);
		
		try {
			// get write lock
			if (locks.get(path) != null) {
				locks.get(path).writeLock().lock();
			} else {
				synchronized(map_lock) {
					if (locks.get(path) == null) {
						locks.put(path, new ReentrantReadWriteLock());
					}
					locks.get(path).writeLock().lock();
				}
			}
			// write back the shallow copy to master copy
			copyFileUsingFileStreams(tem_path, path);
			Path tmp = Paths.get(tem_path);
			Files.delete(tmp);
			return new File(path).lastModified();
		} catch (IOException e) {
			return -1;
		} finally {
			// release lock
			locks.get(path).writeLock().unlock();
		}
	}


	/**
	 * Unlink a file
	 * @param path
	 * @return Error message if error, null if success
	 * @throws RemoteException
     */
	public String unlink(String path) throws RemoteException {
		path = rootdir + path;
		File file = new File(path);

		// get write lock
		if (locks.get(path) != null) {
			locks.get(path).writeLock().lock();
		}

		// error handling
		if (!file.exists()) {
			if (locks.get(path) != null) {
				locks.get(path).writeLock().unlock();
			}
			return "ENOENT";
		}

		if (file.isDirectory()) {
			if (locks.get(path) != null) {
				locks.get(path).writeLock().unlock();
			} 
			return "EISDIR";
		}
		
		// delete files
		try {
			Path tmp = Paths.get(path);
			Files.delete(tmp);
			return null;
		} catch (SecurityException e) {
			return "EPERM";
		} catch (NoSuchFileException x) {
			String errno = "ENOENT";
			if (x.getMessage().contains("Permission")) errno = "EACCESS";
			return errno;
		} catch (IOException e) {
			if (e.getMessage().contains("Bad file descriptor")) return "EBADF";
			else if (e.getMessage().contains("Permission")) return "EACCESS";
			return "EIO";
		} finally {
			if (locks.get(path) != null) {
				locks.get(path).writeLock().unlock();
				synchronized(map_lock) {
					locks.remove(path);
				}
			} 
		}
	}

	/**
	 * Open a file at path.
	 * Return file's metadata. If new version detected, return file data as well.
	 * @param path: file path
	 * @param option: open operation(1-create, 2-createnew, 3-read, 4-write)
	 * @param version: cache latest version
	 * @return FileData class contains file's metadata, null if not in rootdir
	 * @throws RemoteException
	 */
	public FileData open(String path, int option, long version) throws RemoteException {
		// if not in root directory
		path = rootdir + getOrigPath(path);
		File file = new File(path);
		if (!isSubDirectory(file)) { return null; }
		FileData file_data = new FileData(0, new byte[0]);

		// get read lock
		if (locks.get(path) != null) {
			locks.get(path).readLock().lock();
		} else {
			synchronized(map_lock) {
				if (locks.get(path) == null) {locks.put(path, new ReentrantReadWriteLock());}
				locks.get(path).readLock().lock();
			}
		}

		// if file exist
		if (file.exists()) {
			file_data.isExist = true;

			// for create_new return error
			if (option == 2) {
				file_data.isError = true;
				if (locks.get(path) != null) {locks.get(path).readLock().unlock();}
				return file_data;
			}

			// if is directory
			if (file.isDirectory()) {
				if (locks.get(path) != null) {locks.get(path).readLock().unlock();}
				file_data.isDir = true;
			} else {
				// if not directory, read data according to version
				String mode = "rw";
				if (option == 3)  mode = "r";
				try {
					RandomAccessFile raf = new RandomAccessFile(path, mode);
					long server_version = file.lastModified();
					// if new version detected, return data as well
					if (server_version > version) {
						long size = file.length();
						byte[] data = new byte[MaxLen <= (int)size? MaxLen:(int)size];
						raf.read(data);
						raf.close();
						file_data.version = server_version;
						file_data.len = size;
						file_data.data = data;
					}
				} catch (FileNotFoundException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				} catch (SecurityException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				} catch (IOException e) {
					file_data.isError = true;
					file_data.ErrorMsg = e.getMessage();
				} finally {
					if(locks.get(path) != null) {locks.get(path).readLock().unlock();}
				}
			}
		}

		// if file not exist
		else {
			file_data.isExist = false;

			// if not exist, return error for read and write
			if (option >= 3) {
				if (locks.get(path) != null) {locks.get(path).readLock().unlock();}
				return file_data;
			}

			// if is directory
			if (file.isDirectory()) {
				if (locks.get(path) != null) {locks.get(path).readLock().unlock();}
				file_data.isDir = true;
			} else {
				// create new file if it is not directory
				if (option <= 2) {
					try { file.createNewFile();
					} catch (IOException e) {
						file_data.isError = true;
						file_data.ErrorMsg = e.getMessage();
					} finally {
						if(locks.get(path) != null) {locks.get(path).readLock().unlock();}
					}
					file_data.len = file.length();
					file_data.version = file.lastModified();
				}
			}
		}

		return file_data;
	}

	/**
	 * Read data from a file at offset, maximum reading size MaxLen
	 * Used for chunking read.
	 * @param path: file path
	 * @param offset: file pointer
	 * @return FileReadData class contains data read
	 * @throws RemoteException
     */
	@Override
	public FileReadData read(String path, long offset) throws RemoteException {
		path = rootdir + getOrigPath(path);
		try {
			// get read lock
			synchronized(map_lock) {
				if (locks.get(path) == null) {
					locks.put(path, new ReentrantReadWriteLock());
				}
				locks.get(path).readLock().lock();
			}

			// read data
			RandomAccessFile raf = new RandomAccessFile(path, "rw");
			raf.seek(offset);
			byte[] buf = new byte[MaxLen];
			int size = raf.read(buf, 0, MaxLen);
			offset = raf.getFilePointer();
			raf.close();
			if (size < MaxLen) return new FileReadData(offset, Arrays.copyOf(buf, size), size);
			return new FileReadData(offset, buf, size);
		} catch (IOException e) {
			e.printStackTrace(System.err);
		} finally {
			if (locks.get(path) != null) {
				locks.get(path).readLock().unlock();
			}
		}
		return null;
	}


	/**
	 * Write back data to a file at offset from buf of size bytes
	 * Used for chunking write back to shallow copy.
	 * @param path
	 * @param offset: file pointer
	 * @param buf data buffer
	 * @param size: buffer size
	 * @return next file pointer after write
	 * @throws RemoteException
     */
	@Override
	public long write(String path, long offset, byte[] buf, int size) throws RemoteException {
		path = rootdir + getOrigPath(path);
		try {
			RandomAccessFile raf = new RandomAccessFile(path, "rw");
			raf.seek(offset);
			raf.write(buf, 0, size);;
			offset = raf.getFilePointer();
			raf.close();
			return offset;
		} catch (IOException e) {
			e.printStackTrace(System.err);
			return -1;
		} 
	}


	/**
	 * Copy file from path str1 to path str2
	 * @param str1: first file path
	 * @param str2: second file path
	 * @throws IOException
     */
	private static void copyFileUsingFileStreams(String str1, String str2)
			throws IOException {
		File source =new File(str1);
		File dest =new File(str2);
		if (!dest.exists()) dest.createNewFile();
		InputStream input = null;
		OutputStream output = null;
		try {
			input = new FileInputStream(source);
			output = new FileOutputStream(dest);
			byte[] buf = new byte[2046];
			int bytesRead;
			while ((bytesRead = input.read(buf)) > 0) {
				output.write(buf, 0, bytesRead);
			}
		} finally {
			input.close();
			output.close();
		}
	}


	/**
	 * Recursively check if a file belongs to root directory
	 * @param file object
	 * @return true if in the directory, false otherwise
     */
	private boolean isSubDirectory(File file) {
		File tmp;
		try {
			tmp = file.getCanonicalFile();
		} catch (IOException e) {return false;}

		while (tmp != null) {
			if (root.equals(tmp)) { return true; }
			tmp = tmp.getParentFile();
		}
		return false;
	}


	/**
	 * Map client side path with canonical path
	 * @param new_path: client side path
	 * @return: server side path
     */
	private static String getOrigPath(String new_path) {return new_path.replaceAll("%`%", "/");}
	
	
    public static void main(String args[]) {
        try {
        	 // Bind the remote object's stub in the registry
        	Server server = new Server();
        	LocateRegistry.createRegistry(Integer.parseInt(args[0]));
            Registry registry = LocateRegistry.getRegistry(Integer.parseInt(args[0]));
            registry.bind("RemoteFile", server);

			// root directory setup
            server.rootdir = args[1] + "/";
            server.root = new File(server.rootdir).getCanonicalFile();
            System.err.println("Server ready, rootdir:" + args[1]);
        } catch (Exception e) {
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
        }
    }
}
