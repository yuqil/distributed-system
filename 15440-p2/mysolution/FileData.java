import java.io.Serializable;

/**
 * This is a class for transfer file metadata
 * Author: Yuqi Liu
 */
public class FileData implements Serializable {
	public long len;                  // data size
	public byte[] data;               // file data
	public long version = -1;         // file version
	public boolean isDir = false;     // file is directory?
	public boolean isExist = false;   // file exist
	public boolean isError = false;   // file error occurs?
	public String ErrorMsg;           // file error message
	
	public FileData(long len, byte[] data) {
		this.len = len;
		this.data = data;
	}

	/**
	 * Check if a file is a directory
	 * @return true or false
     */
	public boolean isDirectory() { return this.isDir; }

	/**
	 * Check if a file exists
	 * @return
     */
	public boolean exists() { return this.isExist; }

	/**
	 * Set an error flag and error message
	 * @param error
     */
	public void setError(String error) {
		this.isError = true;
		this.ErrorMsg = error;
	}

	/**
	 * Flush the data.
	 */
	public void flush() {
		data = null;
	}
}

