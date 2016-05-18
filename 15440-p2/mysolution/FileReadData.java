import java.io.Serializable;

/**
 * This is a class for stroing file data for read.
 * Aurhor: Yuqi Liu
 */
public class FileReadData implements Serializable {
	long offset = 0;     // current file pointer
	byte[] data = null;  // read data
	int size = -1;       // data size
	
	public FileReadData(long offset, byte[] data, int size) {
		this.offset = offset;
		this.data = data;
		this.size = size;
	}
}
